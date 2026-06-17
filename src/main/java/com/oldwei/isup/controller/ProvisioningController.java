package com.oldwei.isup.controller;

import com.oldwei.isup.config.HikFeatureProperties;
import com.oldwei.isup.model.Device;
import com.oldwei.isup.model.HttpStatus;
import com.oldwei.isup.model.R;
import com.oldwei.isup.model.provisioning.ProvisioningResponse;
import com.oldwei.isup.model.provisioning.ProvisioningStatus;
import com.oldwei.isup.model.provisioning.RawIsapiRequest;
import com.oldwei.isup.model.provisioning.RawIsapiResponse;
import com.oldwei.isup.model.provisioning.UserDeleteRequest;
import com.oldwei.isup.model.provisioning.UserSyncRequest;
import com.oldwei.isup.model.provisioning.UserVerificationResponse;
import com.oldwei.isup.service.BridgeAuthService;
import com.oldwei.isup.service.DeviceCacheService;
import com.oldwei.isup.service.HikvisionProvisioningService;
import com.oldwei.isup.service.RawIsapiDiagnosticService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

@Slf4j
@RestController
@RequestMapping("/api/devices/{deviceId}")
@RequiredArgsConstructor
public class ProvisioningController {

    private static final String TOKEN_HEADER = "X-Flow-Bridge-Token";

    private final HikFeatureProperties hikFeatureProperties;
    private final BridgeAuthService bridgeAuthService;
    private final DeviceCacheService deviceCacheService;
    private final HikvisionProvisioningService provisioningService;
    private final RawIsapiDiagnosticService rawIsapiDiagnosticService;

    @PutMapping("/users/{employeeNo}")
    public ResponseEntity<R<ProvisioningResponse>> syncUser(
            @PathVariable String deviceId,
            @PathVariable String employeeNo,
            @RequestHeader(value = TOKEN_HEADER, required = false) String token,
            @RequestBody(required = false) UserSyncRequest request) {

        ResponseEntity<R<ProvisioningResponse>> guard = guardProvisioning(deviceId, employeeNo, token);
        if (guard != null) {
            return guard;
        }

        if (request == null || request.getEmployee() == null) {
            return validationError(deviceId, employeeNo, "employee is required.");
        }

        if (!StringUtils.equals(employeeNo, request.getEmployee().getEmployeeNo())) {
            return validationError(deviceId, employeeNo, "Path employeeNo must match body.employee.employeeNo.");
        }

        Optional<Device> deviceOpt = onlineDevice(deviceId);
        if (deviceOpt.isEmpty()) {
            return offline(deviceId, employeeNo);
        }

        ProvisioningResponse result = provisioningService.syncUser(deviceOpt.get(), employeeNo, request);
        if (ProvisioningStatus.NOT_IMPLEMENTED.equals(result.getBridgeStatus())) {
            return response(HttpStatus.NOT_IMPLEMENTED, "Provisioning is not implemented yet.", result);
        }
        if (ProvisioningStatus.FAILED.equals(result.getBridgeStatus())) {
            return response(HttpStatus.ERROR, "Provisioning failed.", result);
        }

        return ResponseEntity.ok(R.ok(result));
    }

    @DeleteMapping("/users/{employeeNo}")
    public ResponseEntity<R<ProvisioningResponse>> deleteUser(
            @PathVariable String deviceId,
            @PathVariable String employeeNo,
            @RequestHeader(value = TOKEN_HEADER, required = false) String token,
            @RequestBody(required = false) UserDeleteRequest request) {

        ResponseEntity<R<ProvisioningResponse>> guard = guardProvisioning(deviceId, employeeNo, token);
        if (guard != null) {
            return guard;
        }

        UserDeleteRequest safeRequest = request != null ? request : new UserDeleteRequest();

        Optional<Device> deviceOpt = onlineDevice(deviceId);
        if (deviceOpt.isEmpty()) {
            return offline(deviceId, employeeNo);
        }

        ProvisioningResponse result = provisioningService.deleteUser(deviceOpt.get(), employeeNo, safeRequest);
        if (ProvisioningStatus.NOT_IMPLEMENTED.equals(result.getBridgeStatus())) {
            return response(HttpStatus.NOT_IMPLEMENTED, "Provisioning is not implemented yet.", result);
        }

        return ResponseEntity.ok(R.ok(result));
    }

    @GetMapping("/users/{employeeNo}/verify")
    public ResponseEntity<R<UserVerificationResponse>> verifyUser(
            @PathVariable String deviceId,
            @PathVariable String employeeNo,
            @RequestHeader(value = TOKEN_HEADER, required = false) String token) {

        if (!bridgeAuthService.isAuthorized(token)) {
            UserVerificationResponse body = userVerificationResponse(deviceId, employeeNo, ProvisioningStatus.UNAUTHORIZED, false, "", null);
            return response(HttpStatus.UNAUTHORIZED, "Unauthorized.", body);
        }

        if (!hikFeatureProperties.getProvisioning().isEnabled() || !hikFeatureProperties.getRawIsapi().isEnabled()) {
            UserVerificationResponse body = userVerificationResponse(deviceId, employeeNo, ProvisioningStatus.FEATURE_DISABLED, false, "", null);
            return response(HttpStatus.SERVICE_UNAVAILABLE, "User verification endpoint is disabled.", body);
        }

        Optional<Device> deviceOpt = onlineDevice(deviceId);
        if (deviceOpt.isEmpty()) {
            UserVerificationResponse body = userVerificationResponse(deviceId, employeeNo, ProvisioningStatus.OFFLINE, false, "", "offline");
            return response(HttpStatus.CONFLICT, "Device is not online.", body);
        }

        UserVerificationResponse result = provisioningService.verifyUser(deviceOpt.get(), employeeNo);
        if (ProvisioningStatus.FAILED.equals(result.getBridgeStatus())) {
            return response(HttpStatus.ERROR, "User verification failed.", result);
        }

        return ResponseEntity.ok(R.ok(result));
    }

    @PostMapping("/isapi")
    public ResponseEntity<R<RawIsapiResponse>> rawIsapi(
            @PathVariable String deviceId,
            @RequestHeader(value = TOKEN_HEADER, required = false) String token,
            @RequestBody(required = false) RawIsapiRequest request) {

        if (!bridgeAuthService.isAuthorized(token)) {
            RawIsapiResponse body = rawIsapiDiagnosticService.rejected(deviceId, request, ProvisioningStatus.UNAUTHORIZED);
            return response(HttpStatus.UNAUTHORIZED, "Unauthorized.", body);
        }

        if (!hikFeatureProperties.getRawIsapi().isEnabled()) {
            RawIsapiResponse body = rawIsapiDiagnosticService.rejected(deviceId, request, ProvisioningStatus.FEATURE_DISABLED);
            return response(HttpStatus.SERVICE_UNAVAILABLE, "Raw ISAPI diagnostic endpoint is disabled.", body);
        }

        if (!rawIsapiDiagnosticService.isAllowed(request)) {
            RawIsapiResponse body = rawIsapiDiagnosticService.rejected(
                    deviceId,
                    request,
                    "Only GET /ISAPI/System/deviceInfo and GET /ISAPI/AccessControl/UserInfo/capabilities are allowed."
            );
            return response(HttpStatus.BAD_REQUEST, "Unsafe or unsupported ISAPI diagnostic request.", body);
        }

        Optional<Device> deviceOpt = onlineDevice(deviceId);
        if (deviceOpt.isEmpty()) {
            RawIsapiResponse body = rawIsapiDiagnosticService.rejected(deviceId, request, ProvisioningStatus.OFFLINE);
            return response(HttpStatus.CONFLICT, "Device is not online.", body);
        }

        RawIsapiResponse body = rawIsapiDiagnosticService.execute(deviceOpt.get(), request);
        if (RawIsapiDiagnosticService.STATUS_FAILED.equals(body.getStatus())) {
            return response(HttpStatus.ERROR, "Raw ISAPI passthrough failed.", body);
        }
        return ResponseEntity.ok(R.ok(body));
    }

    private ResponseEntity<R<ProvisioningResponse>> guardProvisioning(String deviceId, String employeeNo, String token) {
        if (!bridgeAuthService.isAuthorized(token)) {
            ProvisioningResponse body = baseResponse(deviceId, employeeNo, ProvisioningStatus.UNAUTHORIZED);
            return response(HttpStatus.UNAUTHORIZED, "Unauthorized.", body);
        }

        if (!hikFeatureProperties.getProvisioning().isEnabled()) {
            ProvisioningResponse body = baseResponse(deviceId, employeeNo, ProvisioningStatus.FEATURE_DISABLED);
            return response(HttpStatus.SERVICE_UNAVAILABLE, "Provisioning endpoint is disabled.", body);
        }

        return null;
    }

    private Optional<Device> onlineDevice(String deviceId) {
        return deviceCacheService.getByDeviceId(deviceId)
                .filter(device -> Integer.valueOf(1).equals(device.getIsOnline()))
                .filter(device -> device.getLoginId() != null && device.getLoginId() > -1);
    }

    private ResponseEntity<R<ProvisioningResponse>> offline(String deviceId, String employeeNo) {
        ProvisioningResponse body = baseResponse(deviceId, employeeNo, ProvisioningStatus.OFFLINE);
        return response(HttpStatus.CONFLICT, "Device is not online.", body);
    }

    private ResponseEntity<R<ProvisioningResponse>> validationError(String deviceId, String employeeNo, String message) {
        ProvisioningResponse body = baseResponse(deviceId, employeeNo, ProvisioningStatus.VALIDATION_ERROR);
        return response(HttpStatus.BAD_REQUEST, message, body);
    }

    private ProvisioningResponse baseResponse(String deviceId, String employeeNo, String bridgeStatus) {
        return new ProvisioningResponse(null, deviceId, employeeNo, false, false, false, bridgeStatus, "", null);
    }

    private UserVerificationResponse userVerificationResponse(
            String deviceId,
            String employeeNo,
            String bridgeStatus,
            boolean found,
            String rawResponse,
            String sdkError) {
        return new UserVerificationResponse(deviceId, employeeNo, found, bridgeStatus, rawResponse, sdkError);
    }

    private <T> ResponseEntity<R<T>> response(int status, String message, T body) {
        return ResponseEntity.status(status).body(R.fail(status, message, body));
    }
}
