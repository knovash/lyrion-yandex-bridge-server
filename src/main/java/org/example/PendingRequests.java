package org.example;

import org.springframework.stereotype.Component;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class PendingRequests {
    private final Map<String, CompletableFuture<String>> pending = new ConcurrentHashMap<>();

    public CompletableFuture<String> create(String correlationId) {
        CompletableFuture<String> f = new CompletableFuture<>();
        pending.put(correlationId, f);
        return f;
    }

    public void complete(String correlationId, String responseJson) {
        CompletableFuture<String> f = pending.remove(correlationId);
        if (f != null) f.complete(responseJson);
    }
}