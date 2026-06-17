package com.oldwei.isup.service;

/**
 * Face-upload protocol variant. Mirrors the strings configured under
 * {@code hik.provisioning.face-upload-mode} so the bridge can ship multiple
 * device-family shapes behind one endpoint.
 *
 * <p>Each enum exposes:
 * <ul>
 *   <li>{@link #method}: HTTP verb for the ISAPI path;</li>
 *   <li>{@link #isapiPath}: the ISAPI path with {@code format=json};</li>
 *   <li>{@link #imageFieldName}: the multipart field name carrying the JPEG.</li>
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
