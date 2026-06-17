package com.oldwei.isup.service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Face-upload protocol variant. Mirrors the strings configured under
 * {@code hik.provisioning.face-upload-mode} so the bridge can ship multiple
 * device-family shapes behind one endpoint.
 *
 * <p>Each enum exposes:
 * <ul>
 *   <li>{@link #method()}: HTTP verb for the ISAPI path;</li>
 *   <li>{@link #isapiPath()}: the ISAPI path (without query string);</li>
 *   <li>{@link #imageFieldName()}: the multipart field name carrying the JPEG;</li>
 *   <li>{@link #faceRecordJson(String)}: the BARE JSON object placed in the
 *       {@code FaceDataRecord} part for this mode.</li>
 * </ul>
 */
public enum FaceUploadMode {

    /**
     * Default variant on most Hikvision access-control devices.
     * {@code POST /ISAPI/Intelligent/FDLib/FaceDataRecord?format=json},
     * image field {@code FaceImage}.
     */
    FACE_DATA_RECORD_FACEIMAGE(
            "POST",
            "/ISAPI/Intelligent/FDLib/FaceDataRecord",
            "FaceImage"
    ),

    /**
     * Same path as {@link #FACE_DATA_RECORD_FACEIMAGE} but the binary image
     * part is named {@code img} (used by some iVMS firmware revisions).
     */
    FACE_DATA_RECORD_IMG(
            "POST",
            "/ISAPI/Intelligent/FDLib/FaceDataRecord",
            "img"
    ),

    /**
     * {@code PUT /ISAPI/Intelligent/FDLib/FDSetUp?format=json}, image field
     * {@code img}. Some Hikvision ISAPI firmwares expose face enrolment under
     * this path and require {@code PUT}.
     */
    FD_SETUP_IMG(
            "PUT",
            "/ISAPI/Intelligent/FDLib/FDSetUp",
            "img"
    );

    private final String method;
    private final String isapiPath;
    private final String imageFieldName;

    FaceUploadMode(String method, String isapiPath, String imageFieldName) {
        this.method = method;
        this.isapiPath = isapiPath;
        this.imageFieldName = imageFieldName;
    }

    public String method() {
        return method;
    }

    public String isapiPath() {
        return isapiPath;
    }

    public String imageFieldName() {
        return imageFieldName;
    }

    /**
     * Builds the BARE JSON object emitted into the {@code FaceDataRecord}
     * multipart part. Hikvision's multipart parser reads the part raw bytes
     * directly as the descriptor object, so we never wrap it in an outer
     * {@code {"FaceDataRecord":{...}}} key.
     *
     * <p>The shape is mode-dependent because the two endpoints accept slightly
     * different field sets on access-control firmware:
     * <ul>
     *   <li>{@code FaceDataRecord} endpoints want the FDLib face descriptor
     *       with {@code faceLibType}, {@code FDID}, {@code FPID}.</li>
     *   <li>{@code FDSetUp} additionally expects {@code employeeNo} so the
     *       enrolled face binds to the access-control user - omitting it makes
     *       the device return {@code statusCode=6 / MessageParametersLack}.</li>
     * </ul>
     */
    public String faceRecordJson(String employeeNo) {
        Map<String, Object> record = new LinkedHashMap<>();
        switch (this) {
            case FD_SETUP_IMG -> {
                // FDSetUp endpoint: bind to the access-control user explicitly.
                record.put("employeeNo", employeeNo);
                record.put("faceLibType", "blackFD");
                record.put("FDID", "1");
                record.put("FPID", employeeNo);
            }
            default -> {
                // FaceDataRecord endpoints: the canonical FDLib descriptor.
                record.put("faceLibType", "blackFD");
                record.put("FDID", "1");
                record.put("FPID", employeeNo);
            }
        }
        return com.alibaba.fastjson2.JSON.toJSONString(record);
    }

    /**
     * Resolves a raw config string into a {@link FaceUploadMode}. Any unknown
     * or blank value falls back to the {@link #FACE_DATA_RECORD_FACEIMAGE
     * default} so a malformed env var never breaks face uploads.
     */
    public static FaceUploadMode fromConfig(String raw) {
        if (raw == null || raw.isBlank()) {
            return FACE_DATA_RECORD_FACEIMAGE;
        }
        return switch (raw.trim().toLowerCase()) {
            case "face-data-record-faceimage" -> FACE_DATA_RECORD_FACEIMAGE;
            case "face-data-record-img" -> FACE_DATA_RECORD_IMG;
            case "fd-setup-img" -> FD_SETUP_IMG;
            default -> FACE_DATA_RECORD_FACEIMAGE;
        };
    }
}
