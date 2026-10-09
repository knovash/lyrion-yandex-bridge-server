package org.example;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * GET /info — выдаёт клиентам общие ключи/идентификаторы (навык УДЯ, TTS).
 * Значения из application.properties, перекрываются env:
 *   SKILL_ID, SKILL_DEV_TOKEN, TTS_API_KEY
 *
 * Требует Authorization: Bearer <instanceToken> (выдаётся при авторизации),
 * чтобы токен разработчика навыка не был публичным.
 */
@RestController
public class InfoController {

    private final TokenRegistry tokens;
    private final YandexUserResolver userResolver;

    @Value("${info.skill-id:}")
    private String skillId;

    @Value("${info.skill-dev-token:}")
    private String skillDevToken;

    @Value("${info.tts-api-key:}")
    private String ttsApiKey;

    public InfoController(TokenRegistry tokens, YandexUserResolver userResolver) {
        this.tokens = tokens;
        this.userResolver = userResolver;
    }

    @GetMapping("/info")
    public ResponseEntity<Map<String, String>> info(@RequestHeader(value = "Authorization", required = false) String auth) {
        if (auth == null || !auth.startsWith("Bearer ")) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "no bearer token"));
        }
        String token = auth.substring("Bearer ".length()).trim();
        String uid = tokens.uidByToken(token);
        if (uid == null) {
            // как в WS: instanceToken мог потеряться при рестарте сервера — принимаем Яндекс access_token
            uid = userResolver.uidByAccessToken(token);
        }
        if (uid == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "invalid token"));
        }

        Map<String, String> result = new LinkedHashMap<>();
        result.put("skillId", skillId == null ? "" : skillId);
        result.put("yandextSkillTokenDeveloper", skillDevToken == null ? "" : skillDevToken);
        result.put("yandexSstTttsApiKey", ttsApiKey == null ? "" : ttsApiKey);
        return ResponseEntity.ok(result);
    }
}
