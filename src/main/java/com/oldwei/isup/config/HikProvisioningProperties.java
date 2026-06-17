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
     * </ul>
     * <p>For URL-based enrollment use one of the {@code face-url*} modes
     * documented in {@link com.oldwei.isup.service.FaceUrlShape}.
     */
    private String faceUploadMode = "face-data-record-faceimage";

    /**
     * Selects the FACE_URL payload shape. Only meaningful when
     * {@link #faceUploadMode} resolves to one of the {@code face-url*} modes.
     * Env: {@code FLOW_HIK_FACE_URL_SHAPE}. See
     * {@link com.oldwei.isup.service.FaceUrlShape} for the exact JSON each
     * value produces.
     */
    private String faceUrlShape = "flat-faceurl-upper";

    /**
     * Top-level {@code faceLibType} value sent in FACE_URL payloads. Sourced
     * from the device capability discovery ({@code GET /ISAPI/Intelligent/FDLib}).
     * Env: {@code FLOW_HIK_FACE_LIB_TYPE}.
     */
    private String faceLibType = "blackFD";

    /**
     * FDLib id ({@code FDID}) to enroll into. Defaults to {@code 1}, matching
     * the {@code blackFD} library reported by the DS-K1T321MFWX. Env:
     * {@code FLOW_HIK_FACE_FDID}.
     */
    private String fdid = "1";
}

