package com.oldwei.isup.model.provisioning;

import lombok.Data;

import java.util.Map;

/**
 * Body for {@code POST /api/devices/{deviceId}/events/search}.
 *
 * <p>Maps directly onto the Hikvision {@code AcsEventCond} directive passed
 * to {@code /ISAPI/AccessControl/AcsEvent?format=json} via
 * {@link com.oldwei.isup.sdk.service.impl.CmsUtil#passThroughBytesWithStatus}.
 *
 * <p>All fields are optional; the bridge fills in sensible defaults
 * (searchID, maxResults, searchResultPosition) when the caller omits them,
 * matching the integration Laravel already uses for attendance sync.
 */
@Data
public class AttendanceEventsSearchRequest {

    /** Caller-provided correlation id (e.g. {@code laravel-sync-<uuid>}). */
    private String searchID;

    /** 0-based page offset. Defaults to 0. */
    private Integer searchResultPosition;

    /** Page size. Defaults to 30 to match Laravel's FLOW_HIK_SYNC_LIMIT. */
    private Integer maxResults;

    /** Major event filter. 0 = all (Hikvision convention). */
    private Integer major;

    /** Minor event filter. 0 = all (Hikvision convention). */
    private Integer minor;

    /** ISO-8601 start, e.g. {@code 2026-06-17T00:00:00-05:00}. Required. */
    private String startTime;

    /** ISO-8601 end. Required. */
    private String endTime;

    /**
     * Optional raw override. When non-null, it is sent verbatim as the
     * {@code AcsEventCond} object (after JSON merge with the typed fields
     * above, where typed fields win on conflict).
     */
    private Map<String, Object> raw;
}
