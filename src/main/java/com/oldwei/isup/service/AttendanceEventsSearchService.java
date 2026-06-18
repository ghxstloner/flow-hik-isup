package com.oldwei.isup.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oldwei.isup.model.Device;
import com.oldwei.isup.model.provisioning.AttendanceEventsSearchRequest;
import com.oldwei.isup.model.provisioning.AttendanceEventsSearchResponse;
import com.oldwei.isup.sdk.service.impl.CmsUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Proxies a {@code POST /ISAPI/AccessControl/AcsEvent?format=json} search to
 * the device through the ISUP/EHome pass-through channel.
 *
 * <p>Unlike {@link RawIsapiDiagnosticService} - which intentionally blocks any
 * path not in its read-only allowlist - this service is dedicated to the ACS
 * attendance search use-case and emits a JSON body compliant with the
 * {@code AcsEventCond} directive documented in the Hikvision ISAPI spec.
 *
 * <p>Output bytes are exactly what the device returned; Laravel's existing
 * {@code IsapiResponseParser} handles the {@code InfoList} / {@code totalMatches}
 * / {@code responseStatusStrg} extraction without any bridge-side coercion.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AttendanceEventsSearchService {

    public static final String STATUS_SUCCESS = "success";
    public static final String STATUS_FAILED = "failed";

    /**
     * Defaults used when Laravel does not send the field.
     * {@code maxResults=30} matches the {@code FLOW_HIK_SYNC_LIMIT=30}
     * convention cited in the Laravel integration brief.
     */
    private static final int DEFAULT_MAX_RESULTS = 30;
    private static final int DEFAULT_POSITION = 0;
    private static final int EVENT_PASSTHROUGH_TIMEOUT_MS = 15000;
    private static final String ACS_EVENT_URL = "POST /ISAPI/AccessControl/AcsEvent?format=json";

    private final CmsUtil cmsUtil;
    private final ObjectMapper objectMapper;

    public AttendanceEventsSearchResponse search(Device device, AttendanceEventsSearchRequest request) {
        Map<String, Object> acsEventCond = buildAcsEventCond(request);
        String json;
        try {
            json = objectMapper.writeValueAsString(Map.of("AcsEventCond", acsEventCond));
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize AcsEventCond: deviceId={}, err={}",
                    device.getDeviceId(), e.getMessage());
            return new AttendanceEventsSearchResponse(
                    device.getDeviceId(),
                    request != null ? request.getSearchID() : null,
                    STATUS_FAILED,
                    "",
                    "serialise_failed:" + e.getMessage(),
                    0
            );
        }

        byte[] payload = json.getBytes(StandardCharsets.UTF_8);

        log.info("ACS event search: deviceId={}, loginId={}, searchID={}, position={}, maxResults={}, range=[{}..{}]",
                device.getDeviceId(),
                device.getLoginId(),
                acsEventCond.get("searchID"),
                acsEventCond.get("searchResultPosition"),
                acsEventCond.get("maxResults"),
                acsEventCond.get("startTime"),
                acsEventCond.get("endTime"));

        CmsUtil.IsapiPassThroughResult result = cmsUtil.passThroughBytesWithStatus(
                device.getLoginId(),
                ACS_EVENT_URL,
                payload,
                EVENT_PASSTHROUGH_TIMEOUT_MS
        );

        String raw = result.getRawResponse() == null ? "" : result.getRawResponse();
        String status = result.isSuccess() && StringUtils.isBlank(result.getSdkError())
                ? STATUS_SUCCESS
                : STATUS_FAILED;

        return new AttendanceEventsSearchResponse(
                device.getDeviceId(),
                (String) acsEventCond.get("searchID"),
                status,
                raw,
                result.getSdkError(),
                raw.length()
        );
    }

    private Map<String, Object> buildAcsEventCond(AttendanceEventsSearchRequest request) {
        Map<String, Object> cond = new LinkedHashMap<>();

        if (request != null && request.getRaw() != null) {
            // Start from caller raw to carry any vendor-specific field we don't model.
            cond.putAll(request.getRaw());
        }

        String searchID = request != null && StringUtils.isNotBlank(request.getSearchID())
                ? request.getSearchID()
                : "flow-bridge-" + UUID.randomUUID();
        int position = request != null && request.getSearchResultPosition() != null
                ? request.getSearchResultPosition()
                : DEFAULT_POSITION;
        int maxResults = request != null && request.getMaxResults() != null && request.getMaxResults() > 0
                ? request.getMaxResults()
                : DEFAULT_MAX_RESULTS;
        int major = request != null && request.getMajor() != null ? request.getMajor() : 0;
        int minor = request != null && request.getMinor() != null ? request.getMinor() : 0;

        cond.put("searchID", searchID);
        cond.put("searchResultPosition", position);
        cond.put("maxResults", maxResults);
        cond.put("major", major);
        cond.put("minor", minor);
        if (request != null) {
            if (StringUtils.isNotBlank(request.getStartTime())) {
                cond.put("startTime", request.getStartTime());
            }
            if (StringUtils.isNotBlank(request.getEndTime())) {
                cond.put("endTime", request.getEndTime());
            }
        }
        return cond;
    }
}
