package com.oldwei.isup.controller;

import com.oldwei.isup.config.HikFeatureProperties;
import com.oldwei.isup.model.Device;
import com.oldwei.isup.model.HttpStatus;
import com.oldwei.isup.model.R;
import com.oldwei.isup.model.provisioning.DeviceInfoResponse;
import com.oldwei.isup.service.BridgeAuthService;
import com.oldwei.isup.service.DeviceCacheService;
import com.oldwei.isup.service.DeviceInfoService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

/**
 * Device metadata proxy for {@code GET /ISAPI/System/deviceInfo}.
 *
 * <p>Laravel calls {@code GET /api/devices/{deviceId}/device-info} when it
 * detects a deviceId ISUP whose tenant has no {@code hikvision_device_info}
 * row yet, so it can auto-provision the device with the real serial number
 * and firmware version rather than a placeholder.
 *
 * <p>Auth + feature-flag mirrors {@link AttendanceController}: guarded by
 * {@code X-Flow-Bridge-Token} and {@code hik.features.attendance-events.enabled}
 * (because device-info is a poll-time dependency of the same integration).
 */
@Slf4j
@RestController
@RequestMapping("/api/devices/{deviceId}")
@RequiredArgsConstructor
public class DeviceInfoController {

    private static final String TOKEN_HEADER = "X-Flow-Bridge-Token";

    private final HikFeatureProperties hikFeatureProperties;
    private final BridgeAuthService bridgeAuthService;
    private final DeviceCacheService deviceCacheService;
    private final DeviceInfoService deviceInfoService;

    @GetMapping("/device-info")
    public ResponseEntity<R<DeviceInfoResponse>> getDeviceInfo(
            @PathVariable String deviceId,
            @RequestHeader(value = TOKEN_HEADER, required = false) String token) {

        if (!bridgeAuthService.isAuthorized(token)) {
            return response(HttpStatus.UNAUTHORIZED, "Unauthorized.", empty(deviceId));
        }

        if (!hikFeatureProperties.getAttendanceEvents().isEnabled()) {
            return response(HttpStatus.SERVICE_UNAVAILABLE,
                    "Device info endpoint is disabled.",
                    empty(deviceId));
        }

        Optional<Device> deviceOpt = onlineDevice(deviceId);
        if (deviceOpt.isEmpty()) {
            return response(HttpStatus.CONFLICT, "Device is not online.", empty(deviceId));
        }

        DeviceInfoResponse body = deviceInfoService.fetch(deviceOpt.get());
        if (DeviceInfoService.STATUS_FAILED.equals(body.getStatus())) {
            return response(HttpStatus.ERROR, "Device info passthrough failed.", body);
        }
        return ResponseEntity.ok(R.ok(body));
    }

    private Optional<Device> onlineDevice(String deviceId) {
        return deviceCacheService.getByDeviceId(deviceId)
                .filter(device -> Integer.valueOf(1).equals(device.getIsOnline()))
                .filter(device -> device.getLoginId() != null && device.getLoginId() > -1);
    }

    private DeviceInfoResponse empty(String deviceId) {
        return DeviceInfoResponse.builder()
                .deviceId(deviceId)
                .status(DeviceInfoService.STATUS_FAILED)
                .sdkError(null)
                .rawResponse("")
                .build();
    }

    private <T> ResponseEntity<R<T>> response(int status, String message, T body) {
        return ResponseEntity.status(status).body(R.fail(status, message, body));
    }
}
