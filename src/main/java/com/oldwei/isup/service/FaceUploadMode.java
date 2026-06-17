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
    ),

    /**
     * URL-based face enrollment. No binary multipart. The bridge publishes the
     * normalized JPEG at a temporary unguessable internal URL via
     * {@link com.oldwei.isup.service.FaceUrlStore} and sends a JSON-only
     * request containing that URL in the Hikvision {@code faceUrl} field to
     * {@code POST /ISAPI/Intelligent/FDLib/FaceDataRecord?format=json}.
     *
     * <p>This is the escape hatch for device families whose firmware cannot
     * parse the binary multipart {@code badJsonFormat} path - the device
     * fetches the JPEG over HTTP just like iVMS-4200 "add by URL".
     */
    FACE_URL(
            "POST",
            "/ISAPI/Intelligent/FDLib/FaceDataRecord",
            null
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
     * Builds the JSON string for this mode.
     *
     * <p>Why this shape: the direct-IP Laravel implementation (the only one
     * proven to work on this device family through Guzzle multipart) emits the
     * WRAPPED key {@code {"FaceDataRecord":{...}}} - claims that the Hikvision
     * multipart parser strips the part name and re-attaches this outer key.
     * Earlier bare-object attempts produced {@code badJsonFormat} on the
     * DS-K1T321MFWX, so the wrapper is restored here to mirror Laravel exactly
     * and isolate the remaining variable to the binary-transfer path.
     *
     * <p>The {@code FACE_URL} mode emits a flat JSON body with a
     * {@code faceUrl} field that the device fetches over HTTP - the binary
     * multipart path is skipped entirely.
     */
    public String faceRecordJson(String employeeNo, String faceUrl) {
        Map<String, Object> body = new LinkedHashMap<>();
        Map<String, Object> record = new LinkedHashMap<>();
        switch (this) {
            case FACE_URL -> {
                record.put("faceLibType", "blackFD");
                record.put("FDID", "1");
                record.put("FPID", employeeNo);
                record.put("faceUrl", faceUrl);
                body.put("FaceDataRecord", record);
                return com.alibaba.fastjson2.JSON.toJSONString(body);
            }
            case FD_SETUP_IMG -> {
                record.put("employeeNo", employeeNo);
                record.put("faceLibType", "blackFD");
                record.put("FDID", "1");
                record.put("FPID", employeeNo);
                body.put("FaceDataRecord", record);
                return com.alibaba.fastjson2.JSON.toJSONString(body);
            }
            default -> {
                // Mirrors Laravel's HikvisionUserSyncService::sendFace exactly:
                //   {"FaceDataRecord":{"employeeNo":"<e>","faceLibType":"blackFace"}}
                // "blackFace" matches the working direct-IP reference; the
                // earlier "blackFD" guess was never validated on this device.
                record.put("employeeNo", employeeNo);
                record.put("faceLibType", "blackFace");
                body.put("FaceDataRecord", record);
                return com.alibaba.fastjson2.JSON.toJSONString(body);
            }
        }
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
            case "face-url" -> FACE_URL;
            default -> FACE_DATA_RECORD_FACEIMAGE;
        };
    }
}
