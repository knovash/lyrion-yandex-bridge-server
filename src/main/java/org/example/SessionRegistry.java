package org.example;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class SessionRegistry {
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    public void put(String uid, WebSocketSession s) { sessions.put(uid, s); }
    public void remove(String uid)                  { sessions.remove(uid); }

    /**
     * Единственный подключённый клиент (режим одного пользователя).
     * Возвращает null, если клиентов нет или подключено больше одного.
     */
    public synchronized String anyUid() {
        if (sessions.size() != 1) return null;
        return sessions.keySet().iterator().next();
    }

    public boolean send(String uid, String json) {
        WebSocketSession s = sessions.get(uid);
        if (s == null || !s.isOpen()) return false;
        try {
            // WebSocketSession не потокобезопасен — синхронизируемся
            synchronized (s) {
                s.sendMessage(new TextMessage(json));
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}