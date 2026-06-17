package com.oldwei.isup.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Provisioning tuning knobs. These are intentionally separate from the
 * coarse-grained {@link HikFeatureProperties} on/off flags so they can change
 * per-device-firmware behavior without flipping a feature.
 */
@Data
@Component
@ConfigurationProperties(prefix = "hik.provisioning")
public class HikProvisioningProperties {

    /**
     * Selects the Hikvision face-upload protocol variant. Different device
     * families accept different multipart shapes; this lets the operator switch
     * modes without a rebuild.
     *
     * <p>Allowed values (matched case-insensitively in
     * {@link com.oldwei.isup.service.FaceUploadMode}):
     * <ul>
     *   <li>{@code face-data-record-faceimage} (default):<br>
     *       {@code POST /ISAPI/Intelligent/FDLib/FaceDataRecord?format=json}
     *       with parts {@code FaceDataRecord} (bare JSON) + {@code FaceImage}
     *       (binary JPEG).</li>
     *   <li>{@code face-data-record-img}:<br>
     *       Same URL but the binary image part is named {@code img}.</li>
     *   <li>{@code fd-setup-img}:<br>
     *       {@code PUT /ISAPI/Intelligent/FDLib/FDSetUp?format=json}
     *       with parts {@code FaceDataRecord} + {@code img}.</li>
     *   <li>{@code face-url}:<br>
     *       JSON-only {@code POST /ISAPI/Intelligent/FDLib/FaceDataRecord?format=json}
     *       carrying a {@code faceUrl} field. The bridge publishes the JPEG
     *       at a temporary unguessable internal URL (see
     *       {@link com.oldwei.isup.service.FaceUrlStore}) that the device
     *       fetches over HTTP. Escape hatch for firmware that rejects binary
     *       multipart with {@code badJsonFormat}.</li>
     * </ul>
     */
    private String faceUploadMode = "face-data-record-faceimage";
}
