package com.oldwei.isup.model.provisioning;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Parsed {@code GET /ISAPI/System/deviceInfo} response. The bridge calls the
 * device via ISAPI pass-through and extracts the fields Laravel needs to
 * auto-provision a {@code hikvision_device_info} row when it sees a new
 * deviceId ISUP for the first time.
 *
 * <p>Only the fields Laravel currently consumes are modelled; everything else
 * from the raw XML/JSON payload is preserved in {@link #rawResponse} for
 * forward compatibility and operator inspection.
 *
 * <p>Naming mirrors the Hikvision {@code <DeviceInfo>} XML schema so the JSON
 * keys Laravel receives line up with what the device emits on-device.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeviceInfoResponse {
    private String deviceId;
    private String status;
    private String sdkError;

    /** {@code <deviceName>} */
    private String deviceName;
    /** {@code <deviceID>} ISUP deviceId (string, e.g. "1"). */
    private String deviceIdSource;
    /** {@code <model>} */
    private String model;
    /** {@code <serialNumber>} */
    private String serialNumber;
    /** {@code <deviceLocation>}. */
    private String deviceLocation;
    /** {@code <deviceType>} e.g. "doorController". May come back blank. */
    private String hikDeviceType;
    /** {@code <firmwareVersion>} */
    private String firmwareVersion;
    /** {@code <firmwareReleasedDate>} */
    private String firmwareReleasedDate;
    /** {@code <deviceTextOverlay>} display name shown to operators. */
    private String deviceTextOverlay;
    /** {@code <isSupport*>} family is intentionally not modelled here. */
    /** Raw XML/JSON body so Laravel can extract any extra field. */
    private String rawResponse;
}
