package com.oldwei.isup.model.provisioning;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Result of an ACS event search passthrough.
 *
 * <p>Mirrors the shape of {@link RawIsapiResponse} but is paginated and
 * semantically scoped to attendance - Laravel uses {@link #eventsJson()}
 * to extract {@code InfoList}, {@code responseStatusStrg} and
 * {@code totalMatches} exactly as its existing ISAPI parser does.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AttendanceEventsSearchResponse {

    private String deviceId;
    private String searchID;
    private String status;
    /** Raw Hikvision JSON body ({@code {"AcsEvent":{...}}}) untouched. */
    private String eventsJson;
    private String sdkError;
    /** Number of bytes returned by the device (sanity check). */
    private int rawResponseLength;
}
