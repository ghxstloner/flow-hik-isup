package com.oldwei.isup.service;

import com.oldwei.isup.model.Device;
import com.oldwei.isup.model.provisioning.RawIsapiRequest;
import com.oldwei.isup.model.provisioning.RawIsapiResponse;
import com.oldwei.isup.sdk.service.impl.CmsUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class RawIsapiDiagnosticService {

    public static final String STATUS_SUCCESS = "success";
    public static final String STATUS_FAILED = "failed";

    private static final Set<String> ALLOWED_GET_PATHS = Set.of(
            "/ISAPI/System/deviceInfo",
            "/ISAPI/AccessControl/UserInfo/capabilities"
    );

    private final CmsUtil cmsUtil;

    public boolean isAllowed(RawIsapiRequest request) {
        return request != null
                && StringUtils.equalsIgnoreCase("GET", request.getMethod())
                && ALLOWED_GET_PATHS.contains(normalizePath(request.getPath()));
    }

    public RawIsapiResponse rejected(String deviceId, RawIsapiRequest request, String sdkError) {
        return new RawIsapiResponse(
                deviceId,
                request != null ? normalizeMethod(request.getMethod()) : null,
                request != null ? normalizePath(request.getPath()) : null,
                STATUS_FAILED,
                "",
                sdkError
        );
    }

    public RawIsapiResponse execute(Device device, RawIsapiRequest request) {
        String method = normalizeMethod(request.getMethod());
        String path = normalizePath(request.getPath());
        String requestUrl = method + " " + path;

        log.info("Raw ISAPI diagnostic requested: deviceId={}, loginId={}, method={}, path={}",
                device.getDeviceId(), device.getLoginId(), method, path);

        CmsUtil.IsapiPassThroughResult result = cmsUtil.passThroughWithStatus(device.getLoginId(), requestUrl, "");
        return new RawIsapiResponse(
                device.getDeviceId(),
                method,
                path,
                result.isSuccess() ? STATUS_SUCCESS : STATUS_FAILED,
                result.getRawResponse(),
                result.getSdkError()
        );
    }

    private String normalizeMethod(String method) {
        return method == null ? null : method.trim().toUpperCase();
    }

    private String normalizePath(String path) {
        return path == null ? null : path.trim();
    }
}
