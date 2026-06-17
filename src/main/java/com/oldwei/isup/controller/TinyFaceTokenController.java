package com.oldwei.isup.controller;

import com.oldwei.isup.service.FaceUrlStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.TimeUnit;

/**
 * Serves the JPEG bytes backing a temporary {@link FaceUrlStore} token to a
 * Hikvision device performing URL-based face enrollment.
 *
 * <p>The route is intentionally short-lived (each token is one-shot, 5-min TTL
 * enforced by the store) and the token is 192 bits of {@link java.security.SecureRandom},
 * so the URL is effectively unguessable. There is no auth header on this path
 * on purpose: the Hikvision device cannot be configured to send
 * {@code X-Flow-Bridge-Token} when it fetches {@code faceUrl}. The token itself
 * is the credential.
 *
 * <p>The path prefix is configurable via {@code hik.face-url.path-prefix} and
 * must match the prefix passed by {@link FaceUrlStore#publish}.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class TinyFaceTokenController {

    private static final MediaType IMAGE_JPEG = MediaType.IMAGE_JPEG_JPEG;

    private final FaceUrlStore faceUrlStore;

    @GetMapping(value = "${hik.face-url.path-prefix:/internal/face/}{token}")
    public ResponseEntity<byte[]> fetchFace(@PathVariable String token) {
        FaceUrlStore.FaceEntry entry = faceUrlStore.consume(token);
        if (entry == null || entry.imageBytes() == null || entry.imageBytes().length == 0) {
            log.warn("Face token not found / already consumed / expired: tokenPrefix={}...",
                    token == null ? "" : token.substring(0, Math.min(6, token.length())));
            return ResponseEntity.notFound().build();
        }
        log.info("Face token served: employeeNo={}, bytes={}",
                entry.employeeNo(), entry.imageBytes().length);
        return ResponseEntity.ok()
                .contentType(IMAGE_JPEG)
                .cacheControl(CacheControl.maxAge(0, TimeUnit.SECONDS).mustRevalidate().cachePrivate())
                .body(entry.imageBytes());
    }
}
