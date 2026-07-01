package com.oldwei.isup.model.provisioning;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Provisioning result envelope.
 *
 * <p>Two face-error channels coexist:
 * <ul>
 *   <li>{@link #sdkError}: SDK-level transport error (a numeric
 *       {@code NET_ECMS_GetLastError()} string) or {@code null}. Set only when
 *       the SDK call itself failed (timeout, no login, etc.).</li>
 *   <li>{@link #photoErrorCode} / {@link #photoSubStatusCode}: structured,
 *       stable face-enrollment error codes derived from the device's
 *       {@code rawResponse} (e.g. {@code FACE_ALREADY_EXISTS} /
 *       {@code deviceUserAlreadyExistFace}, {@code PHOTO_REJECTED_BY_DEVICE} /
 *       {@code SubpicAnalysisModelingError}, or {@code NO_PHOTO_PROVIDED}).</li>
 * </ul>
 *
 * <p>{@link ProvisioningStatus#PARTIAL} is returned with HTTP 200 whenever
 * {@code userSynced=true} but {@code photoSynced=false}; the access-control
 * user is usable, the photo just needs a separate retry.
 */
@Data
@NoArgsConstructor
public class ProvisioningResponse {
    private String correlationId;
    private String deviceId;
    private String employeeNo;
    private Boolean userSynced;
    private Boolean photoSynced;
    private Boolean deleted;
    private String bridgeStatus;
    private String rawResponse;
    private String sdkError;
    /**
     * Structured, stable photo-error code (e.g.
     * {@code FACE_ALREADY_EXISTS}, {@code PHOTO_REJECTED_BY_DEVICE},
     * {@code NO_PHOTO_PROVIDED}) or {@code null} when the photo either
     * succeeded or was never attempted. Companion to
     * {@link #photoSubStatusCode}, which carries the raw device token.
     */
    private String photoErrorCode;
    /**
     * Raw device-level token behind {@link #photoErrorCode}
     * (e.g. {@code deviceUserAlreadyExistFace},
     * {@code SubpicAnalysisModelingError}). Never user-facing.
     */
    private String photoSubStatusCode;

    /**
     * Backwards-compatible 9-arg constructor. Leaves the structured photo
     * error fields null. Preserved so every existing call site keeps compiling
     * without churn.
     */
    public ProvisioningResponse(
            String correlationId,
            String deviceId,
            String employeeNo,
            Boolean userSynced,
            Boolean photoSynced,
            Boolean deleted,
            String bridgeStatus,
            String rawResponse,
            String sdkError) {
        this(correlationId, deviceId, employeeNo, userSynced, photoSynced, deleted,
                bridgeStatus, rawResponse, sdkError, null, null);
    }
}

