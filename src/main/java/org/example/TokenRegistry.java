package org.example;

import org.springframework.stereotype.Component;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * В реальной жизни: таблица в БД. Здесь — in-memory для примера.
 * Ключ: instanceToken (длинный случайный, выдан клиенту при авторизации).
 * Значение: yandexUid (идентификатор пользователя в Яндексе).
 */
@Component
public class TokenRegistry {
    private final Map<String, String> tokens = new ConcurrentHashMap<>();

    public TokenRegistry() {
        tokens.put("ins_8f3a1c2e9b7d4f6a", "user-1");
        tokens.put("ins_5d6e2b8a1c4f7e9d", "user-2");
    }

    public String uidByToken(String token) {
        if (token == null) return null;
        return tokens.get(token);
    }

    /** Регистрация новой пары instanceToken -> uid (выдаётся при авторизации в Яндексе). */
    public void put(String token, String uid) {
        if (token == null || token.isEmpty() || uid == null || uid.isEmpty()) return;
        tokens.put(token, uid);
    }
}