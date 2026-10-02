package org.example;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

/**
 * Подробное логирование всех HTTP запросов к серверу и ответов сервера.
 *
 * Для каждого запроса: метод, полный URL, все заголовки, тело;
 * для каждого ответа: статус, время обработки, заголовки, тело.
 * Заголовки Authorization маскируются (первые символы + длина), чтобы не светить токены.
 *
 * Шумные пути (клиент опрашивает их каждые 3 сек в ожидании авторизации)
 * логируются одной краткой строкой: /token, /token_spotify, /refresh_spotify, /favicon.ico.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestResponseLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger("http");

    private static final int MAX_BODY = 4000;

    /** WebSocket handshake (/ws) нельзя оборачивать в кэширующие wrappers - пропускаем. */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri != null && uri.startsWith("/ws");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        ContentCachingRequestWrapper req = new ContentCachingRequestWrapper(request);
        ContentCachingResponseWrapper resp = new ContentCachingResponseWrapper(response);
        long start = System.currentTimeMillis();

        boolean quiet = isQuietPath(request.getRequestURI());
        try {
            chain.doFilter(req, resp);
        } finally {
            long ms = System.currentTimeMillis() - start;
            try {
                log(req, resp, ms, quiet);
            } catch (Exception e) {
                log.warn("LOGGING ERROR: {}", e.toString());
            }
            // обязательно: вернуть закэшированное тело ответа клиенту
            resp.copyBodyToResponse();
        }
    }

    private void log(ContentCachingRequestWrapper req, ContentCachingResponseWrapper resp, long ms, boolean quiet) {
        HttpServletRequest request = (HttpServletRequest) req.getRequest();

        if (quiet) {
            log.info("--> {} {} (body {} b) <-- {} in {} ms",
                    request.getMethod(), fullUrl(request), req.getContentAsByteArray().length, resp.getStatus(), ms);
            return;
        }

        log.info("================================================================");
        log.info("--> {} {}", request.getMethod(), fullUrl(request));
        log.info("--> remote: {}:{}", request.getRemoteAddr(), request.getRemotePort());
        log.info("--> headers:\n{}", requestHeaders(request));

        String reqBody = bodyToString(req.getContentAsByteArray());
        log.info("--> body ({} b): {}", req.getContentAsByteArray().length, reqBody);

        log.info("<-- status {} in {} ms", resp.getStatus(), ms);
        log.info("<-- headers:\n{}", responseHeaders(resp));

        String respBody = bodyToString(resp.getContentAsByteArray());
        log.info("<-- body ({} b): {}", resp.getContentAsByteArray().length, respBody);
        log.info("================================================================");
    }

    private String fullUrl(HttpServletRequest request) {
        String url = request.getRequestURL().toString();
        String query = request.getQueryString();
        return query == null ? url : url + "?" + query;
    }

    private String requestHeaders(HttpServletRequest request) {
        StringBuilder sb = new StringBuilder();
        for (String name : Collections.list(request.getHeaderNames())) {
            sb.append("    ").append(name).append(": ").append(maskIfNeeded(name, request.getHeader(name))).append('\n');
        }
        return sb.toString().trim();
    }

    private String responseHeaders(ContentCachingResponseWrapper resp) {
        StringBuilder sb = new StringBuilder();
        for (String name : resp.getHeaderNames()) {
            sb.append("    ").append(name).append(": ").append(maskIfNeeded(name, resp.getHeader(name))).append('\n');
        }
        return sb.toString().trim();
    }

    /** Токены авторизации не пишем в лог целиком. */
    private String maskIfNeeded(String headerName, String value) {
        if (value == null) return "";
        if (headerName != null && headerName.toLowerCase().contains("authorization")) {
            if (value.length() <= 16) return "***(" + value.length() + " chars)";
            return value.substring(0, 12) + "...(" + value.length() + " chars)";
        }
        return value;
    }

    private String bodyToString(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return "";
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (text.isEmpty()) return "";
        if (!isPrintable(text)) return "(binary " + bytes.length + " bytes)";
        if (text.length() > MAX_BODY) return text.substring(0, MAX_BODY) + "...(truncated " + (text.length() - MAX_BODY) + " chars)";
        return text.replace('\n', ' ').replace('\r', ' ');
    }

    private boolean isPrintable(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t') return false;
        }
        return true;
    }

    private boolean isQuietPath(String uri) {
        if (uri == null) return false;
        return uri.equals("/token") || uri.equals("/token_spotify")
                || uri.equals("/refresh_spotify") || uri.equals("/favicon.ico");
    }
}
