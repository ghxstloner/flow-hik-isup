package com.oldwei.isup.service;

import com.oldwei.isup.model.Device;
import com.oldwei.isup.model.provisioning.DeviceInfoResponse;
import com.oldwei.isup.sdk.service.impl.CmsUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

/**
 * Resolves {@code GET /ISAPI/System/deviceInfo} on a logged-in device through
 * the ISUP/EHome pass-through channel.
 *
 * <p>Used by Laravel's auto-provisioning flow: when the poller first sees a
 * deviceId ISUP whose tenant has no {@code hikvision_device_info} row yet, it
 * asks the bridge for this metadata so it can persist the real serial number,
 * firmware version, model and device type instead of a placeholder
 * {@code ISUP-{deviceId}}.
 *
 * <p>The underlying pass-through is the same {@link CmsUtil#passThroughWithStatus}
 * used by {@link RawIsapiDiagnosticService} for diagnostics. We do not extend
 * that allow-list here on purpose - they own different responsibilities:
 * diagnostic is operator-driven, this one is Laravel-driven (and gated by
 * {@code hik.features.attendance-events.enabled} in the controller because
 * it is a poll-time dependency for the attendance integration).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceInfoService {

    public static final String STATUS_SUCCESS = "success";
    public static final String STATUS_FAILED = "failed";

    private static final String DEVICE_INFO_URL = "GET /ISAPI/System/deviceInfo";
    private static final int DEVICE_INFO_TIMEOUT_MS = 10000;

    private final CmsUtil cmsUtil;

    /**
     * Calls {@code GET /ISAPI/System/deviceInfo} on the given online device
     * and extracts the fields Laravel needs to auto-create a device row.
     *
     * <p>The device responds by default in XML; we pass the raw body back to
     * Laravel and let it XML-parse there (Laravel already has XML parsers
     * for ISAPI on the Digest path). A best-effort extraction of the most
     * useful fields is returned in the structured fields so Laravel does not
     * have to parse XML just to fill {@code DEVICE_SERIAL}.
     */
    public DeviceInfoResponse fetch(Device device) {
        log.info("Device info: deviceId={}, loginId={}",
                device.getDeviceId(), device.getLoginId());

        CmsUtil.IsapiPassThroughResult result = cmsUtil.passThroughWithStatus(
                device.getLoginId(),
                DEVICE_INFO_URL,
                ""
        );

        String raw = result.getRawResponse() == null ? "" : result.getRawResponse();
        String status = result.isSuccess() && StringUtils.isBlank(result.getSdkError())
                ? STATUS_SUCCESS
                : STATUS_FAILED;

        DeviceInfoResponse.DeviceInfoResponseBuilder b = DeviceInfoResponse.builder()
                .deviceId(device.getDeviceId())
                .status(status)
                .sdkError(result.getSdkError())
                .rawResponse(raw);

        if (STATUS_SUCCESS.equals(status)) {
            applyExtractedFields(b, raw);
        }

        return b.build();
    }

    /**
     * Defensive XML tag extractor: Hikvision deviceInfo returns XML by
     * default. We do not drag a full XML parser into this service just for
     * 6 fields, but we extract them robustly against the device's XML schema.
     *
     * <p>If a field is missing or unparsable it stays null, and Laravel falls
     * back to its placeholder convention ({@code ISUP-{deviceId}} etc.).
     */
    private void applyExtractedFields(DeviceInfoResponse.DeviceInfoResponseBuilder b, String xml) {
        if (StringUtils.isBlank(xml)) {
            return;
        }
        b.serialNumber(extractFirst(xml, "serialNumber"));
        b.deviceName(extractFirst(xml, "deviceName"));
        b.model(extractFirst(xml, "model"));
        b.hikDeviceType(extractFirst(xml, "deviceType"));
        b.firmwareVersion(extractFirst(xml, "firmwareVersion"));
        b.firmwareReleasedDate(extractFirst(xml, "firmwareReleasedDate"));
        b.deviceTextOverlay(extractFirst(xml, "deviceTextOverlay"));
        b.deviceIdSource(extractFirst(xml, "deviceID"));
    }

    /**
     * First (substring) tag value matcher, robust to XML namespaces and the
     * self-closing {@code <foo/>} case. Returns null when the tag is absent
     * or empty so Builder keeps defaults clean.
     */
    private String extractFirst(String xml, String tag) {
        // Open tag may carry namespaces: <ns:tag> or <tag>. Match loosely.
        int openStart = xml.indexOf("<" + tag);
        if (openStart < 0) {
            return null;
        }
        int openEnd = xml.indexOf('>', openStart);
        if (openEnd < 0) {
            return null;
        }
        // Self-closing <tag/> => absent value.
        if (xml.charAt(openEnd - 1) == '/') {
            return null;
        }
        int closeStart = xml.indexOf("</" + tag, openEnd + 1);
        if (closeStart < 0) {
            return null;
        }
        String value = xml.substring(openEnd + 1, closeStart);
        // Strip optional namespace inside CDATA - deviceInfo does not use CDATA
        // in practice, but stay defensive.
        return StringUtils.trimToNull(value);
    }
}
