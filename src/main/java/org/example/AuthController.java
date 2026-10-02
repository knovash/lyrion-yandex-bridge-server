package org.example;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;

/**
 * OAuth-брокер (по образцу CloudServer / CloudServerSpoty из squeeze-alice-cloud):
 * браузер пользователя ходит в Яндекс/Spotify через этот сервер, обмен кода на токен
 * выполняется здесь (client_secret не покидает сервер), клиент забирает токены
 * одноразовым GET /token?state=... и GET /token_spotify?state=...
 */
@RestController
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private static final String YANDEX_AUTHORIZE_URL = "https://oauth.yandex.ru/authorize";
    private static final String YANDEX_TOKEN_URL = "https://oauth.yandex.ru/token";
    private static final String YANDEX_USER_INFO_URL = "https://login.yandex.ru/info?format=json";
    private static final String SPOTIFY_AUTHORIZE_URL = "https://accounts.spotify.com/authorize";
    private static final String SPOTIFY_TOKEN_URL = "https://accounts.spotify.com/api/token";
    private static final String SPOTIFY_SCOPE =
            "app-remote-control " +
            "user-read-private " +
            "user-read-email " +
            "user-read-playback-state " +
            "user-read-currently-playing " +
            "user-modify-playback-state";

    private static final SecureRandom random = new SecureRandom();

    private final AuthStore authStore;
    private final TokenRegistry tokenRegistry;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @Value("${domain}")
    private String domain;

    @Value("${yandex.client-id}")
    private String yandexClientId;

    @Value("${yandex.client-secret}")
    private String yandexClientSecret;

    @Value("${spotify.client-id}")
    private String spotifyClientId;

    @Value("${spotify.client-secret}")
    private String spotifyClientSecret;

    public AuthController(AuthStore authStore, TokenRegistry tokenRegistry) {
        this.authStore = authStore;
        this.tokenRegistry = tokenRegistry;
    }

    // -------------------- Яндекс --------------------

    /** Редирект на страницу входа Яндекса (открывается по ссылке с локальной страницы клиента). */
    @GetMapping("/authorize")
    public ResponseEntity<Void> authorizeYandex(@RequestParam String state) {
        log.info("AUTH YANDEX: redirect state={}", state);
        String url = YANDEX_AUTHORIZE_URL +
                "?response_type=code" +
                "&client_id=" + yandexClientId +
                "&redirect_uri=" + enc(domain + "/callback") +
                "&force_confirm=true" +
                "&state=" + enc(state);
        return redirect(url);
    }

    /** Сюда возвращает Яндекс после входа пользователя. Обмен кода на токен. */
    @GetMapping("/callback")
    public ResponseEntity<String> callbackYandex(@RequestParam(required = false) String code,
                                                 @RequestParam(required = false) String state,
                                                 @RequestParam(required = false) String error) {
        log.info("CALLBACK YANDEX: state={} error={}", state, error);
        if (error != null || code == null || state == null) {
            return html(HttpStatus.BAD_REQUEST, "Authorization failed: " + error);
        }
        try {
            String tokenResponse = postForm(YANDEX_TOKEN_URL,
                    "grant_type=authorization_code" +
                    "&code=" + enc(code) +
                    "&client_id=" + enc(yandexClientId) +
                    "&client_secret=" + enc(yandexClientSecret));
            JsonNode root = mapper.readTree(tokenResponse);
            String accessToken = root.path("access_token").asText("");
            if (accessToken.isEmpty()) {
                log.error("CALLBACK YANDEX: no access_token in response");
                return html(HttpStatus.BAD_GATEWAY, "Token exchange failed");
            }

            // uid и display_name пользователя (обмен токена на информацию, как UtilsToken.getUserInfo)
            JsonNode userInfo = mapper.readTree(getWithOAuth(YANDEX_USER_INFO_URL, accessToken));
            String uid = userInfo.path("id").asText("");
            String name = userInfo.path("display_name").asText("");
            log.info("CALLBACK YANDEX: uid={} name={}", uid, name);

            // instanceToken для WebSocket-канала этого клиента (выдаём сами, как ключи в TokenRegistry)
            String instanceToken = generateInstanceToken();
            tokenRegistry.put(instanceToken, uid);

            AuthStore.Package p = new AuthStore.Package();
            p.yandexToken = accessToken;
            p.instanceToken = instanceToken;
            p.uid = uid;
            p.name = name;
            authStore.put(state, p);

            return html(HttpStatus.OK, "Authorization successful! You can close this page.");
        } catch (Exception e) {
            log.error("CALLBACK YANDEX ERROR", e);
            return html(HttpStatus.INTERNAL_SERVER_ERROR, "Token exchange failed");
        }
    }

    /** Одноразовая выдача пакета токенов Яндекса клиенту (он опрашивает этот эндпоинт). */
    @GetMapping("/token")
    public ResponseEntity<String> tokenYandex(@RequestParam String state) {
        AuthStore.Package p = authStore.take(state);
        if (p == null || p.yandexToken == null || p.yandexToken.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"token\":\"\"}");
        }
        log.info("TOKEN YANDEX: пакет выдан state={} uid={}", state, p.uid);
        ObjectNode json = mapper.createObjectNode();
        json.put("token", p.yandexToken);
        json.put("instanceToken", p.instanceToken);
        json.put("uid", p.uid);
        json.put("name", p.name);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(json.toString());
    }

    // -------------------- Spotify --------------------

    /** Редирект на страницу входа Spotify. */
    @GetMapping("/authorize_spotify")
    public ResponseEntity<Void> authorizeSpotify(@RequestParam String state) {
        log.info("AUTH SPOTIFY: redirect state={}", state);
        String url = SPOTIFY_AUTHORIZE_URL +
                "?response_type=code" +
                "&client_id=" + spotifyClientId +
                "&scope=" + enc(SPOTIFY_SCOPE) +
                "&redirect_uri=" + enc(domain + "/spoti_callback") +
                "&state=" + enc(state);
        return redirect(url);
    }

    /** Сюда возвращает Spotify после входа пользователя. Обмен кода на токены. */
    @GetMapping("/spoti_callback")
    public ResponseEntity<String> callbackSpotify(@RequestParam(required = false) String code,
                                                  @RequestParam(required = false) String state,
                                                  @RequestParam(required = false) String error) {
        log.info("CALLBACK SPOTIFY: state={} error={}", state, error);
        if (error != null || code == null || state == null) {
            return html(HttpStatus.BAD_REQUEST, "Authorization failed: " + error);
        }
        try {
            String tokenResponse = postForm(SPOTIFY_TOKEN_URL,
                    "grant_type=authorization_code" +
                    "&code=" + enc(code) +
                    "&redirect_uri=" + enc(domain + "/spoti_callback"),
                    spotifyBasicAuth());
            JsonNode root = mapper.readTree(tokenResponse);
            String accessToken = root.path("access_token").asText("");
            if (accessToken.isEmpty()) {
                log.error("CALLBACK SPOTIFY: no access_token in response");
                return html(HttpStatus.BAD_GATEWAY, "Token exchange failed");
            }
            String refreshToken = root.path("refresh_token").asText("");
            long expiresIn = root.path("expires_in").asLong(3600);
            long expiresAt = System.currentTimeMillis() + expiresIn * 1000L;
            log.info("CALLBACK SPOTIFY: expires in {} sec", expiresIn);

            AuthStore.Package p = new AuthStore.Package();
            p.spotifyAccessToken = accessToken;
            p.spotifyRefreshToken = refreshToken;
            p.spotifyExpiresAt = expiresAt;
            authStore.put(state, p);

            return html(HttpStatus.OK, "Authorization successful! You can close this page.");
        } catch (Exception e) {
            log.error("CALLBACK SPOTIFY ERROR", e);
            return html(HttpStatus.INTERNAL_SERVER_ERROR, "Token exchange failed");
        }
    }

    /** Одноразовая выдача пакета токенов Spotify клиенту. */
    @GetMapping("/token_spotify")
    public ResponseEntity<String> tokenSpotify(@RequestParam String state) {
        AuthStore.Package p = authStore.take(state);
        if (p == null || p.spotifyAccessToken == null || p.spotifyAccessToken.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"access_token\":\"\"}");
        }
        log.info("TOKEN SPOTIFY: пакет выдан state={}", state);
        ObjectNode json = mapper.createObjectNode();
        json.put("access_token", p.spotifyAccessToken);
        json.put("refresh_token", p.spotifyRefreshToken);
        json.put("expires_at", p.spotifyExpiresAt);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(json.toString());
    }

    /**
     * Обновление access-токена Spotify по refresh-токену клиента.
     * client_secret живёт только на сервере (в squeeze-alice это делал облак по MQTT, здесь — по HTTP).
     */
    @PostMapping("/refresh_spotify")
    public ResponseEntity<String> refreshSpotify(@RequestBody String rawBody) {
        log.info("REFRESH SPOTIFY: start");
        try {
            JsonNode body = mapper.readTree(rawBody);
            String refreshToken = body.path("refresh_token").asText("");
            if (refreshToken.isEmpty()) {
                return ResponseEntity.badRequest()
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"no refresh_token\"}");
            }
            String tokenResponse = postForm(SPOTIFY_TOKEN_URL,
                    "grant_type=refresh_token" +
                    "&refresh_token=" + enc(refreshToken),
                    spotifyBasicAuth());
            JsonNode root = mapper.readTree(tokenResponse);
            if (root.path("access_token").asText("").isEmpty()) {
                return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"refresh failed\"}");
            }
            ObjectNode json = mapper.createObjectNode();
            json.put("access_token", root.path("access_token").asText());
            json.put("expires_in", root.path("expires_in").asLong());
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(json.toString());
        } catch (Exception e) {
            log.error("REFRESH SPOTIFY ERROR", e);
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"error\":\"refresh failed\"}");
        }
    }

    // -------------------- Корень (страница-статус, вместо 404) --------------------

    @GetMapping("/")
    public ResponseEntity<String> index() {
        return html(HttpStatus.OK,
                "sa-ws-server is running<br><br>" +
                "REST: POST /api/yandex<br>" +
                "WebSocket: /ws<br>" +
                "Авторизация клиента: http://localhost:8888/auth (Яндекс) и /auth_spotify (Spotify)");
    }

    // -------------------- helpers --------------------

    private ResponseEntity<Void> redirect(String url) {
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(url)).build();
    }

    private ResponseEntity<String> html(HttpStatus status, String body) {
        return ResponseEntity.status(status)
                .contentType(MediaType.TEXT_HTML)
                .body("<html><head><meta charset='utf-8'></head><body>" + body + "</body></html>");
    }

    private String spotifyBasicAuth() {
        return "Basic " + Base64.getEncoder().encodeToString(
                (spotifyClientId + ":" + spotifyClientSecret).getBytes(StandardCharsets.UTF_8));
    }

    /** POST form-urlencoded. Тело ответа при 200 не логируется (там токены). */
    private String postForm(String url, String form, String... authorization) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form));
        if (authorization.length > 0) builder.header("Authorization", authorization[0]);
        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 200) {
            log.info("POST {} -> 200", url);
        } else {
            log.warn("POST {} -> {} body: {}", url, response.statusCode(), response.body());
        }
        return response.body();
    }

    /** GET с заголовком Authorization: OAuth (обмен токена Яндекса на информацию о пользователе). */
    private String getWithOAuth(String url, String oauthToken) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "OAuth " + oauthToken)
                .GET()
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        log.info("GET {} -> {}", url, response.statusCode());
        return response.body();
    }

    /** Случайный instanceToken вида ins_<32 hex> — как тестовые ключи в TokenRegistry. */
    private String generateInstanceToken() {
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        StringBuilder sb = new StringBuilder("ins_");
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}