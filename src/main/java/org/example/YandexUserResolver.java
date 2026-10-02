package org.example;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Мультипользовательская маршрутизация: Яндекс access_token -> числовой yandexUid.
 * Тот же uid выдаётся клиенту в instanceToken при OAuth (AuthController -> TokenRegistry),
 * поэтому токен из входящего запроса (навык /alice/ — body или заголовок, УДЯ /v1.0/ —
 * заголовок Authorization) однозначно указывает, какому клиенту адресован запрос.
 */
@Component
public class YandexUserResolver {

    private static final Logger log = LoggerFactory.getLogger(YandexUserResolver.class);
    private static final String USER_INFO_URL = "https://login.yandex.ru/info?format=json";
    private static final long TTL_OK_MS = 6L * 60 * 60 * 1000;   // успешный резолв
    private static final long TTL_FAIL_MS = 5L * 60 * 1000;      // негативный кэш

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    private static class Entry {
        final String uid;       // null = негативный результат
        final long expiresAt;

        Entry(String uid, long expiresAt) {
            this.uid = uid;
            this.expiresAt = expiresAt;
        }
    }

    private final Map<String, Entry> cache = new ConcurrentHashMap<>();

    /** Числовой yandexUid или null (токен отсутствует/недействителен). */
    public String uidByAccessToken(String token) {
        if (token == null || token.isEmpty()) return null;
        Entry e = cache.get(token);
        long now = System.currentTimeMillis();
        if (e != null && e.expiresAt > now) return e.uid;
        String uid = resolve(token);
        cache.put(token, new Entry(uid, now + (uid != null ? TTL_OK_MS : TTL_FAIL_MS)));
        return uid;
    }

    private String resolve(String token) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(USER_INFO_URL))
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "OAuth " + token)
                    .GET()
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.info("RESOLVE TOKEN -> HTTP {} (токен не валиден)", response.statusCode());
                return null;
            }
            String uid = mapper.readTree(response.body()).path("id").asText("");
            log.info("RESOLVE TOKEN -> uid={}", uid);
            return uid.isEmpty() ? null : uid;
        } catch (Exception ex) {
            log.warn("RESOLVE TOKEN error: {}", ex.getMessage());
            return null;
        }
    }
}
