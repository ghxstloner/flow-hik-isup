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
            @RequestBody(required = false) AttendanceEventsSearchRequest request) {

        if (!bridgeAuthService.isAuthorized(token)) {
            return response(HttpStatus.UNAUTHORIZED, "Unauthorized.", empty(deviceId, request));
        }

        if (!hikFeatureProperties.getAttendanceEvents().isEnabled()) {
            return response(HttpStatus.SERVICE_UNAVAILABLE,
                    "Attendance events search endpoint is disabled.",
                    empty(deviceId, request));
        }

        if (request == null || request.getStartTime() == null || request.getEndTime() == null
                || request.getStartTime().isBlank() || request.getEndTime().isBlank()) {
            return response(HttpStatus.BAD_REQUEST,
                    "startTime and endTime are required (ISO-8601).",
                    empty(deviceId, request));
        }

        Optional<Device> deviceOpt = onlineDevice(deviceId);
        if (deviceOpt.isEmpty()) {
            return response(HttpStatus.CONFLICT, "Device is not online.", empty(deviceId, request));
        }

        AttendanceEventsSearchResponse body = eventsSearchService.search(deviceOpt.get(), request);

        if (AttendanceEventsSearchService.STATUS_FAILED.equals(body.getStatus())) {
            return response(HttpStatus.ERROR, "ACS event search passthrough failed.", body);
        }
        return ResponseEntity.ok(R.ok(body));
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
