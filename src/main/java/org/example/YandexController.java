package org.example;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.Enumeration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@RestController
public class YandexController {

    private static final Logger log = LoggerFactory.getLogger(YandexController.class);

    private final SessionRegistry sessions;
    private final PendingRequests pending;
    private final YandexUserResolver userResolver;
    private final ObjectMapper mapper = new ObjectMapper();

    public YandexController(SessionRegistry sessions, PendingRequests pending, YandexUserResolver userResolver) {
        this.sessions = sessions;
        this.pending = pending;
        this.userResolver = userResolver;
    }

    /**
     * Сервер НЕ парсит запрос Яндекса: тело уходит клиенту сырой строкой,
     * заголовки — одной строкой, метод/путь/query — как есть из servlet API.
     * Jackson используется только для сериализации конверта (не для разбора).
     * Весь разбор запроса — на стороне клиента.
     *
     * Поддерживаются любые варианты webhook URL и методов УДЯ:
     *  - /api/yandex            (без подпутей)
     *  - /api/yandex/v1.0/...   (префикс отрезается, клиент видит /v1.0/...)
     *  - /v1.0/...              (напрямую на корне)
     * Методы: Яндекс список устройств шлёт GET /v1.0/user/devices,
     * query/action - POST, unlink - DELETE/POST. Клиенту метод передаётся как есть.
     */
    @RequestMapping(
            method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.DELETE, RequestMethod.PUT},
            value = {"/api/yandex", "/api/yandex/**", "/v1.0/**", "/alice", "/alice/**"})
    public String handle(@RequestBody(required = false) String rawBody, HttpServletRequest request) throws Exception {
        if (rawBody == null) rawBody = "";
        String method = request.getMethod();
        String path = request.getRequestURI();
        // клиент роутит запросы УДЯ по /v1.0/...: убираем облачный префикс endpoint
        if (path.startsWith("/api/yandex")) {
            path = path.substring("/api/yandex".length());
        }
        if (path.isEmpty()) path = "/";
        String query = request.getQueryString();
        String url = request.getRequestURL().toString();
        if (query != null) url = url + "?" + query;
        String headers = headersToString(request);

        // --- Лог: только чтение servlet API, никакой разборки ---
        log.info("REQUEST: {} {}", method, url);
        log.info("PATH: {}", path);
        log.info("HEADERS: {}", headers);
        log.info("BODY: {}", rawBody);
        log.info("QUERY: {}", query);

        // --- Маршрутизация (мультипользовательский режим) ---
        // 1) ?uid= из query — админ-оверрайд;
        // 2) access_token запроса (навык: заголовок/body, УДЯ: Authorization) -> yandexUid -> СВОЙ клиент;
        // 3) fallback anyUid — только пока подключён один клиент (режим одного пользователя).
        String uid = uidFromQuery(query);
        if (uid == null) {
            String token = accessTokenForRouting(path, rawBody, request);
            if (token != null) {
                String tokenUid = userResolver.uidByAccessToken(token);
                if (tokenUid != null) {
                    uid = tokenUid;
                    log.info("ROUTE: by access_token -> uid={}", uid);
                }
            }
        }
        if (uid == null) {
            uid = sessions.anyUid();
            if (uid != null) log.info("ROUTE: fallback anyUid (single client) uid={}", uid);
        }
        if (uid == null) {
            log.warn("CLIENT NOT FOUND: no ?uid=, token not resolved and no single connected client");
            return "{\"error\":\"client offline\"}";
        }

        String correlationId = UUID.randomUUID().toString();
        ObjectNode envelope = mapper.createObjectNode();
        envelope.put("correlationId", correlationId);
        ObjectNode req = envelope.putObject("request");
        req.put("method", method);
        req.put("url", url);
        req.put("path", path);
        req.put("query", query);
        req.put("headers", headers);
        req.put("body", rawBody);
        String envelopeJson = mapper.writeValueAsString(envelope);

        // ВАЖНО: future создаётся ДО отправки, иначе мгновенный ответ клиента может потеряться
        CompletableFuture<String> future = pending.create(correlationId);

        boolean ok = sessions.send(uid, envelopeJson);
        if (!ok) {
            pending.complete(correlationId, null); // не оставляем "висящий" future
            log.warn("CLIENT OFFLINE: uid={}", uid);
            return "{\"error\":\"client offline\"}";
        }
        log.info("SENT TO CLIENT uid={} correlationId={}: {}", uid, correlationId, envelopeJson);

        String response;
        try {
            response = future.get(10, TimeUnit.SECONDS); // ждём ответ от клиента
        } catch (TimeoutException e) {
            pending.complete(correlationId, null); // убираем просроченный future
            log.warn("CLIENT RESPONSE TIMEOUT: correlationId={}", correlationId);
            return "{\"error\":\"client timeout\"}";
        }
        if (response == null) response = "{\"error\":\"client offline\"}";

        log.info("RECEIVED FROM CLIENT correlationId={}: {}", correlationId, response);
        return response;
    }

    /** Все заголовки одной строкой "name=[v1, v2]; name2=[v]" — для лога и передачи клиенту. */
    private String headersToString(HttpServletRequest request) {
        StringBuilder sb = new StringBuilder();
        Enumeration<String> names = request.getHeaderNames();
        while (names != null && names.hasMoreElements()) {
            String name = names.nextElement();
            if (sb.length() > 0) sb.append("; ");
            sb.append(name).append("=").append(Collections.list(request.getHeaders(name)));
        }
        return sb.toString();
    }

    /** uid из query-параметра (?uid=user-1) — простой поиск в строке, без разбора JSON. */
    private String uidFromQuery(String query) {
        if (query == null) return null;
        for (String p : query.split("&")) {
            if (p.startsWith("uid=")) return p.substring(4);
        }
        return null;
    }

    /**
     * Токен для маршрутизации: заголовок Authorization (Bearer/OAuth — его шлют и УДЯ,
     * и Диалоги) либо session.user.access_token из тела запроса навыка.
     * Тело клиенту уходит как есть — разбор только для маршрутизации.
     */
    private String accessTokenForRouting(String path, String body, HttpServletRequest request) {
        String auth = request.getHeader("Authorization");
        if (auth != null) {
            if (auth.startsWith("Bearer ")) return auth.substring(7).trim();
            if (auth.startsWith("OAuth ")) return auth.substring(6).trim();
        }
        if (body != null && !body.isEmpty() && body.contains("access_token")) {
            try {
                JsonNode node = mapper.readTree(body);
                String token = node.path("session").path("user").path("access_token").asText("");
                if (!token.isEmpty()) return token;
            } catch (Exception ignored) {
            }
        }
        return null;
    }
}