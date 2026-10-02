package org.example;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory хранилище одноразовых OAuth-пакетов (по образцу tokenStore в squeeze-alice-cloud).
 * Ключ — state (UUID, сгенерирован клиентом), значение — токены, полученные в callback.
 * Пакет выдаётся один раз (take удаляет), невостребованные удаляются по TTL.
 */
@Component
public class AuthStore {

    private static final Logger log = LoggerFactory.getLogger(AuthStore.class);
    private static final long TTL_MS = 15 * 60 * 1000; // 15 минут

    public static class Package {
        public final long createdAt = System.currentTimeMillis();
        // Яндекс
        public String yandexToken;
        public String instanceToken;
        public String uid;
        public String name;
        // Spotify
        public String spotifyAccessToken;
        public String spotifyRefreshToken;
        public long spotifyExpiresAt;
    }

    private final Map<String, Package> store = new ConcurrentHashMap<>();

    public void put(String state, Package p) {
        store.put(state, p);
    }

    /** Забрать пакет (одноразово): токены не выдаются повторно. */
    public Package take(String state) {
        return store.remove(state);
    }

    /** Удаление невостребованных пакетов — токены не висят в памяти вечно. */
    @Scheduled(fixedDelay = 60_000)
    public void cleanupExpired() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, Package>> it = store.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Package> e = it.next();
            if (now - e.getValue().createdAt > TTL_MS) {
                it.remove();
                log.warn("AUTH STORE CLEANUP: state={} expired", e.getKey());
            }
        }
    }
}
