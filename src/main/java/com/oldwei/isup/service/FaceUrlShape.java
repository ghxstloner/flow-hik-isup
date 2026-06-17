package com.oldwei.isup.service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Selectable JSON payload shapes for the URL-based face enrollment modes
 * ({@code face-url*}). Each {@link  FaceUploadMode} that uses URL-based
 * enrollment reads its shape from {@link HikProvisioningProperties#getFaceUrlShape()}.
 *
 * <p>The variant strings below are matched case-insensitively in
 * {@link #fromConfig(String)}; any unknown value falls back to the
 * {@link #FLAT_FACEURL_UPPER} default so a malformed env var cannot break face
 * uploads.
 *
 *<p>Real-device evidence that drove this set (DS-K1T321MFWX
 * {@code GET /ISAPI/Intelligent/FDLib/capabilities}):
 * <ul>
 *   <li>{@code supportFDFunction = "post,delete,put,get,setUp"} (.setUp path)</li>
 *   <li>{@code faceURLLen = 1024} - the capability advertises the field name
 *       {@code faceURL} (capital URL).</li>
 *   <li>{@code faceLibType = "blackFD"} in the FDLib list, FDID 1 exists.</li>
 * </ul>
 */
public enum FaceUrlShape {

    /**
     * Flat top-level JSON, capital-URL key:
     * <pre>{@code
     * {"faceLibType":"blackFD","FDID":"1","FPID":"<e>","faceURL":"http://..."}
     * }</pre>
     * This is the default because the device capability advertises a
     * {@code faceURLLen} field, and {@code errorMsg: faceLibType} on the
     * wrapped payload means the parser looks for {@code faceLibType} at the
     * top level of the JSON root.
     */
    FLAT_FACEURL_UPPER("faceURL", false),

    /**
     * Same flat shape but lowercase URL key, for the alternate Hikvision doc
     * spelling:
     * <pre>{@code
     * {"faceLibType":"blackFD","FDID":"1","FPID":"<e>","faceUrl":"http://..."}
     * }</pre>
     */
    FLAT_FACEURL_LOWER("faceUrl", false),

    /**
     * Wrapped under a {@code FaceDataRecord} object, capital URL:
     * <pre>{@code
     * {"FaceDataRecord":{"faceLibType":"blackFD","FDID":"1","FPID":"<e>","faceURL":"http://..."}}
     * }</pre>
     */
    WRAPPED_FACEURL_UPPER("faceURL", true),

    /**
     * Wrapped, lowercase URL:
     * <pre>{@code
     * {"FaceDataRecord":{"faceLibType":"blackFD","FDID":"1","FPID":"<e>","faceUrl":"http://..."}}
     * }</pre>
     */
    WRAPPED_FACEURL_LOWER("faceUrl", true);

    private final String urlKey;
    private final boolean wrapped;

    FaceUrlShape(String urlKey, boolean wrapped) {
        this.urlKey = urlKey;
        this.wrapped = wrapped;
    }

    public String urlKey() {
        return urlKey;
    }

    public boolean wrapped() {
        return wrapped;
    }

    /**
     * Builds the MAP form of the FACE_URL payload for this shape. The caller
     * serializes it to JSON via fastjson. {@code faceUrl} is inserted verbatim
     * (no escaping) because fastjson handles URL escaping.
     *
     * @param faceLibType configured top-level {@code faceLibType} value
     * @param fdid        configured {@code FDID} value
     * @param employeeNo  used as {@code FPID} (bound to the access-control user)
     * @param faceUrl     the temporary unguessable URL published by FaceUrlStore
     * @return a fastjson-serializable {@link Map}
     */
    public Object buildPayload(String faceLibType, String fdid, String employeeNo, String faceUrl) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("faceLibType", faceLibType);
        record.put("FDID", fdid);
        record.put("FPID", employeeNo);
        record.put(urlKey, faceUrl);
        if (wrapped) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("FaceDataRecord", record);
            return body;
        }
        return record;
    }

    /**
     * Resolves a raw config string into a {@link FaceUrlShape}. Any unknown or
     * blank value falls back to {@link #FLAT_FACEURL_UPPER}.
     */
    public static FaceUrlShape fromConfig(String raw) {
        if (raw == null || raw.isBlank()) {
            return FLAT_FACEURL_UPPER;
        }
        return switch (raw.trim().toLowerCase()) {
            case "flat-faceurl-upper", "face-url" -> FLAT_FACEURL_UPPER;
            case "flat-faceurl-lower" -> FLAT_FACEURL_LOWER;
            case "wrapped-faceurl-upper", "face-url-wrapped-faceurl" -> WRAPPED_FACEURL_UPPER;
            case "wrapped-faceurl-lower" -> WRAPPED_FACEURL_LOWER;
            default -> FLAT_FACEURL_UPPER;
        };
    }
}
