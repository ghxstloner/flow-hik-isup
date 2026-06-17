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
    ),

    /**
     * Lowercase-URL spelling escape hatch. Identical transport to
     * {@link #FACE_URL}; the only difference is the JSON key
     * ({@code faceUrl} vs {@code faceURL}). Useful if a firmware honors the
     * Hikvision doc spelling rather than the capability-advertised one.
     */
    FACE_URL_LOWER(
            "POST",
            "/ISAPI/Intelligent/FDLib/FaceDataRecord",
            null
    ),

    /**
     * Wrapped, capital-URL spelling escape hatch. Same path as {@link #FACE_URL}
     * but emits the descriptor nested under {@code FaceDataRecord}. Kept as a
     * selectable fallback because some firmwares require the wrapper even for
     * URL-based enrollment.
     */
    FACE_URL_WRAPPED(
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
            case "face-url", "face-url-flat-faceurl-upper" -> FACE_URL;
            case "face-url-flat-faceurl-lower" -> FACE_URL_LOWER;
            case "face-url-wrapped-faceurl", "face-url-wrapped-faceurl-upper" -> FACE_URL_WRAPPED;
            default -> FACE_DATA_RECORD_FACEIMAGE;
        };
    }

    /**
     * Builds the multipart {@code FaceDataRecord} part JSON for the binary
     * upload modes. URL-based modes should call
     * {@link #faceRecordJsonUrl(String, String, FaceUrlShape, String, String)}
     * instead - this method throws if invoked on a {@link #isFaceUrlMode()
     * URL mode} to prevent silent fallback to the wrong payload shape.
     *
     * <p>Why this shape: the direct-IP Laravel implementation (the only one
     * proven to work on this device family through Guzzle multipart) emits the
     * WRAPPED key {@code {"FaceDataRecord":{...}}} - claims that the Hikvision
     * multipart parser strips the part name and re-attaches this outer key.
     * Earlier bare-object attempts produced {@code badJsonFormat} on the
     * DS-K1T321MFWX, so the wrapper is restored here to mirror Laravel
     * exactly and isolate the remaining variable to the binary-transfer path.
     */
    public String faceRecordJson(String employeeNo, String faceUrl) {
        if (isFaceUrlMode()) {
            throw new UnsupportedOperationException(
                    "faceRecordJson is for binary multipart modes only; use faceRecordJsonUrl for " + name());
        }
        Map<String, Object> body = new LinkedHashMap<>();
        Map<String, Object> record = new LinkedHashMap<>();
        switch (this) {
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
     * Returns {@code true} when this mode is one of the URL-based enrollment
     * variants ({@link #FACE_URL}, {@link #FACE_URL_LOWER},
     * {@link #FACE_URL_WRAPPED}). Used by the service to dispatch to the
     * JSON-only path instead of binary multipart.
     */
    public boolean isFaceUrlMode() {
        return switch (this) {
            case FACE_URL, FACE_URL_LOWER, FACE_URL_WRAPPED -> true;
            default -> false;
        };
    }

    /**
     * Returns the {@link FaceUrlShape} to use for URL payload assembly at
     * transport time. Kept on the enum (not derived from surfaced config) so
     * the mode and shape stay consistent - users typically only flip
     * face-upload-mode and let this mapping pick the right shape.
     */
    public FaceUrlShape defaultShape() {
        return switch (this) {
            case FACE_URL -> FaceUrlShape.FLAT_FACEURL_UPPER;
            case FACE_URL_LOWER -> FaceUrlShape.FLAT_FACEURL_LOWER;
            case FACE_URL_WRAPPED -> FaceUrlShape.WRAPPED_FACEURL_UPPER;
            default -> FaceUrlShape.FLAT_FACEURL_UPPER;
        };
    }

    /**
     * Builds the FACE_URL JSON payload using the configured shape,
     * {@code faceLibType}, {@code FDID}, and {@code employeeNo} (-> FPID),
     * with the supplied {@code faceUrl} substituted under the shape's URL key.
     * Only meaningful for the {@link #isFaceUrlMode() URL-based modes}. An
     * explicit {@code shapeOverride} wins over {@link #defaultShape()} so the
     * operator can mix any shape with any face-url mode via env.
     */
    public String faceRecordJsonUrl(
            String employeeNo,
            String faceUrl,
            FaceUrlShape shapeOverride,
            String faceLibType,
            String fdid) {
        FaceUrlShape shape = shapeOverride != null ? shapeOverride : defaultShape();
        Object payload = shape.buildPayload(
                faceLibType == null || faceLibType.isBlank() ? "blackFD" : faceLibType,
                fdid == null || fdid.isBlank() ? "1" : fdid,
                employeeNo,
                faceUrl);
        return com.alibaba.fastjson2.JSON.toJSONString(payload);
    }
}
