package com.oldwei.isup.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * In-memory store for one-shot face-image URLs used by the URL-based Hikvision
 * face enrollment flow.
 *
 * <p>When {@code FaceUploadMode.FACE_URL} is selected, the bridge does not send
 * raw JPEG bytes inside a multipart body. Instead it:
 * <ol>
 *   <li>publishes the normalized JPEG at a temporary, unguessable internal
 *       bridge URL produced here;</li>
 *   <li>sends a JSON request to the device containing that URL in the
 *       {@code faceUrl} field;</li>
 *   <li>lets the device fetch the JPEG over HTTP, then evicts the entry.</li>
 * </ol>
 *
 * <p>Security:
 * <ul>
 *   <li>each token is 192 bits of {@link SecureRandom}, URL-safe base64;</li>
 *   <li>each entry self-destructs after {@link #TTL_SECONDS} (default 5 min)
 *       via the cleanup scheduler and is also one-shot (consumed on first
 *       read);</li>
 *   <li>the public base URL comes from {@code hik.stream.http} (already used by
 *       the bridge for stream adverts) so the device can reach the bridge on
 *       its public IP/port.</li>
 * </ul>
 *
 * <p>This store keeps only JPEG bytes in memory; it never persists them and
 * never logs them.
 */
@Slf4j
@Component
public class FaceUrlStore {

    static final long TTL_SECONDS = 300;
    private static final int TOKEN_BYTES = 24; // 192 bits -> 32 url-safe base64 chars

    private final SecureRandom random = new SecureRandom();
    private final ConcurrentHashMap<String, FaceEntry> entries = new ConcurrentHashMap<>();
    private final ScheduledExecutorService gc = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "face-url-store-gc");
        t.setDaemon(true);
        return t;
    });

    @Value("${hik.stream.http.ip:127.0.0.1}")
    private String publicIp;
    @Value("${hik.stream.http.port:80}")
    private String publicPort;
    @Value("${hik.face-url.base-url:}")
    private String configuredBaseUrl;
    @Value("${hik.face-url.path-prefix:/internal/face/}")
    private String pathPrefix;
    @Value("${server.port:16233}")
    private String serverPort;

    @PostConstruct
    void init() {
        gc.scheduleAtFixedRate(this::purgeExpired, TTL_SECONDS, TTL_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * Stores the normalized JPEG and returns the absolute URL the device must
     * GET to retrieve it.
     *
     * @param employeeNo for log/route scoping only; never embedded in the URL
     * @param imageBytes normalized baseline JPEG bytes
     * @return absolute http(s) URL carrying an unguessable token
     */
    public String publish(String employeeNo, byte[] imageBytes) {
        String token = newToken();
        String pathPrefix = normalizePathPrefix();
        Instant expiresAt = Instant.now().plusSeconds(TTL_SECONDS);
        entries.put(token, new FaceEntry(employeeNo, imageBytes, expiresAt));
        String absoluteUrl = baseUrl() + pathPrefix + token;
        log.info("Face URL published: employeeNo={}, tokenPrefix={}..., ttlSeconds={}, bytes={}, url={}",
                employeeNo,
                token.substring(0, Math.min(6, token.length())),
                TTL_SECONDS,
                imageBytes.length,
                absoluteUrl);
        return absoluteUrl;
    }

    /**
     * Returns and consumes the JPEG for the given token. One-shot: a second
     * fetch with the same token returns {@code null}.
     */
    public FaceEntry consume(String token) {
        if (token == null) {
            return null;
        }
        FaceEntry entry = entries.remove(token);
        if (entry == null) {
            return null;
        }
        if (Instant.now().isAfter(entry.expiresAt)) {
            return null;
        }
        return entry;
    }

    /** Evicts everything immediately; used on shutdown/tests. */
    public void clear() {
        entries.clear();
    }

    int size() {
        return entries.size();
    }

    private String baseUrl() {
        if (configuredBaseUrl != null && !configuredBaseUrl.isBlank()) {
            return configuredBaseUrl.replaceAll("/+$", "");
        }
        String host = (publicIp == null || publicIp.isBlank()) ? "127.0.0.1" : publicIp;
        String port = (publicPort == null || publicPort.isBlank()) ? serverPort : publicPort;
        return "http://" + host + ":" + port;
    }

    private String normalizePathPrefix() {
        String p = pathPrefix == null || pathPrefix.isBlank() ? "/internal/face/" : pathPrefix;
        if (!p.startsWith("/")) {
            p = "/" + p;
        }
        if (!p.endsWith("/")) {
            p = p + "/";
        }
        return p;
    }

    private String newToken() {
        byte[] buf = new byte[TOKEN_BYTES];
        random.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    private void purgeExpired() {
        Instant now = Instant.now();
        int before = entries.size();
        entries.entrySet().removeIf(e -> now.isAfter(e.getValue().expiresAt));
        int removed = before - entries.size();
        if (removed > 0) {
            log.debug("Face URL store purged {} expired entr{} (remaining={})",
                    removed, removed == 1 ? "y" : "ies", entries.size());
        }
    }

    /** Read-only summary for diagnostics/metrics. Never includes bytes. */
    public Map<String, Object> describe() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("entries", entries.size());
        out.put("ttlSeconds", TTL_SECONDS);
        out.put("baseUrl", baseUrl());
        return out;
    }

    public record FaceEntry(String employeeNo, byte[] imageBytes, Instant expiresAt) {
    }
}
