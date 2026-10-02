package org.example;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Component
public class SqueezeBoxHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(SqueezeBoxHandler.class);

    private final TokenRegistry tokens;
    private final SessionRegistry sessions;
    private final PendingRequests pending;

    public SqueezeBoxHandler(TokenRegistry tokens, SessionRegistry sessions, PendingRequests pending) {
        this.tokens = tokens;
        this.sessions = sessions;
        this.pending = pending;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String token = extractBearer(session);
        String uid = tokens.uidByToken(token);
        if (uid == null) {
            session.close(new CloseStatus(1008, "invalid token"));
            return;
        }
        session.getAttributes().put("uid", uid);
        sessions.put(uid, session);
        log.info("WS CONNECTED: uid={}", uid);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        // Клиент присылает ответ: {"correlationId": "...", "context": {...}}
        String payload = message.getPayload();
        log.info("RECEIVED FROM CLIENT: {}", payload);
        String correlationId = extractCorrelationId(payload);
        if (correlationId != null) {
            pending.complete(correlationId, payload);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String uid = (String) session.getAttributes().get("uid");
        if (uid != null) {
            sessions.remove(uid);
            log.info("WS DISCONNECTED: uid={} status={}", uid, status);
        }
    }

    private String extractBearer(WebSocketSession session) {
        // Токен через query-параметр ?token=... или через заголовок.
        // Заголовок надёжнее (не попадает в логи прокси).
        String h = session.getHandshakeHeaders().getFirst("Authorization");
        if (h != null && h.startsWith("Bearer ")) return h.substring(7);
        String q = session.getUri() != null ? session.getUri().getQuery() : null;
        if (q != null) {
            for (String p : q.split("&")) {
                if (p.startsWith("token=")) return p.substring(6);
            }
        }
        return null;
    }

    private String extractCorrelationId(String json) {
        // Примитивно, для примера. В реальном коде — Jackson.
        int i = json.indexOf("\"correlationId\"");
        if (i < 0) return null;
        int start = json.indexOf('"', i + 15) + 1;
        int end = json.indexOf('"', start);
        return json.substring(start, end);
    }
}