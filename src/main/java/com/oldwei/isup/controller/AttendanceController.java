package com.oldwei.isup.controller;

import com.oldwei.isup.config.HikFeatureProperties;
import com.oldwei.isup.model.Device;
import com.oldwei.isup.model.HttpStatus;
import com.oldwei.isup.model.R;
import com.oldwei.isup.model.provisioning.AttendanceEventsSearchRequest;
import com.oldwei.isup.model.provisioning.AttendanceEventsSearchResponse;
import com.oldwei.isup.service.AttendanceEventsSearchService;
import com.oldwei.isup.service.BridgeAuthService;
import com.oldwei.isup.service.DeviceCacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

/**
 * ACS attendance event search proxy.
 *
 * <p>Exposes {@code POST /api/devices/{deviceId}/events/search} so Laravel can
 * poll /ISAPI/AccessControl/AcsEvent through the ISUP/EHome channel for
 * devices that are behind NAT and therefore unreachable by HTTP digest from
 * the Laravel server.
 *
 * <p>Auth + feature-flag mirrors {@link ProvisioningController}: guarded by
 * {@code X-Flow-Bridge-Token} and {@code hik.features.attendance-events.enabled}.
 */
@Slf4j
@RestController
@RequestMapping("/api/devices/{deviceId}")
@RequiredArgsConstructor
public class AttendanceController {

    private static final String TOKEN_HEADER = "X-Flow-Bridge-Token";

    private final HikFeatureProperties hikFeatureProperties;
    private final BridgeAuthService bridgeAuthService;
    private final DeviceCacheService deviceCacheService;
    private final AttendanceEventsSearchService eventsSearchService;

    @PostMapping("/events/search")
    public ResponseEntity<R<AttendanceEventsSearchResponse>> searchEvents(
            @PathVariable String deviceId,
            @RequestHeader(value = TOKEN_HEADER, required = false) String token,
            @RequestBody(required = false) java.util.Map<String, Object> body) {

        if (!bridgeAuthService.isAuthorized(token)) {
            return response(HttpStatus.UNAUTHORIZED, "Unauthorized.", empty(deviceId, null));
        }

        if (!hikFeatureProperties.getAttendanceEvents().isEnabled()) {
            return response(HttpStatus.SERVICE_UNAVAILABLE,
                    "Attendance events search endpoint is disabled.",
                    empty(deviceId, null));
        }

        // Normalise the incoming body so both shapes are accepted:
        //   1) Documented flat DTO  :  { startTime, endTime, maxResults, ... }
        //   2) Raw Hikvision wrapper:  { "AcsEventCond": { startTime, endTime, ... } }
        // The flat DTO is what AttendanceEventsSearchRequest binds to, so we
        // only have to unwrap the legacy shape when present.
        java.util.Map<String, Object> flat = normaliseBody(body);

        AttendanceEventsSearchRequest request = toRequest(flat);

        if (request.getStartTime() == null || request.getStartTime().isBlank()
                || request.getEndTime() == null || request.getEndTime().isBlank()) {
            return response(HttpStatus.BAD_REQUEST,
                    "startTime and endTime are required (ISO-8601).",
                    empty(deviceId, request));
        }

        Optional<Device> deviceOpt = onlineDevice(deviceId);
        if (deviceOpt.isEmpty()) {
            return response(HttpStatus.CONFLICT, "Device is not online.", empty(deviceId, request));
        }

        AttendanceEventsSearchResponse rsp = eventsSearchService.search(deviceOpt.get(), request);

        if (AttendanceEventsSearchService.STATUS_FAILED.equals(rsp.getStatus())) {
            return response(HttpStatus.ERROR, "ACS event search passthrough failed.", rsp);
        }
        return ResponseEntity.ok(R.ok(rsp));
    }

    /**
     * Unwrap an eventual {@code "AcsEventCond"} wrapper so both body shapes
     * are accepted. Returns the same map if the body is already flat or null.
     */
    @SuppressWarnings("unchecked")
    private java.util.Map<String, Object> normaliseBody(java.util.Map<String, Object> body) {
        if (body == null) {
            return new java.util.LinkedHashMap<>();
        }
        Object wrapped = body.get("AcsEventCond");
        if (wrapped instanceof java.util.Map<?, ?> cond) {
            // Rebuild as Map<String,Object> for safe typed access downstream.
            java.util.Map<String, Object> flat = new java.util.LinkedHashMap<>();
            for (java.util.Map.Entry<?, ?> e : cond.entrySet()) {
                flat.put(String.valueOf(e.getKey()), e.getValue());
            }
            log.debug("Unwrapped legacy AcsEventCond body for events/search.");
            return flat;
        }
        return body;
    }

    /**
     * Convert the normalised flat map into the typed request, filling in
     * sensible defaults so Laravel callers can omit every field except the
     * mandatory startTime / endTime.
     */
    private AttendanceEventsSearchRequest toRequest(java.util.Map<String, Object> flat) {
        AttendanceEventsSearchRequest request = new AttendanceEventsSearchRequest();
        request.setRaw(flat);

        request.setSearchID(asString(flat.get("searchID"),
                "flow-bridge-" + java.util.UUID.randomUUID()));
        request.setSearchResultPosition(asInt(flat.get("searchResultPosition"), 0));
        request.setMaxResults(asInt(flat.get("maxResults"), 30));
        request.setMajor(asInt(flat.get("major"), 0));
        request.setMinor(asInt(flat.get("minor"), 0));
        request.setStartTime(asString(flat.get("startTime"), null));
        request.setEndTime(asString(flat.get("endTime"), null));
        return request;
    }

    private String asString(Object v, String def) {
        return (v == null || String.valueOf(v).isBlank()) ? def : String.valueOf(v);
    }

    private Integer asInt(Object v, Integer def) {
        if (v == null) {
            return def;
        }
        if (v instanceof Number) {
            return ((Number) v).intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private Optional<Device> onlineDevice(String deviceId) {
        return deviceCacheService.getByDeviceId(deviceId)
                .filter(device -> Integer.valueOf(1).equals(device.getIsOnline()))
                .filter(device -> device.getLoginId() != null && device.getLoginId() > -1);
    }

    private AttendanceEventsSearchResponse empty(String deviceId, AttendanceEventsSearchRequest request) {
        return new AttendanceEventsSearchResponse(
                deviceId,
                request != null ? request.getSearchID() : null,
                AttendanceEventsSearchService.STATUS_FAILED,
                "",
                null,
                0
        );
    }

    private <T> ResponseEntity<R<T>> response(int status, String message, T body) {
        return ResponseEntity.status(status).body(R.fail(status, message, body));
    }
}
