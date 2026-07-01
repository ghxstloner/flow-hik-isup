package com.oldwei.isup.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.oldwei.isup.config.HikProvisioningProperties;
import com.oldwei.isup.model.Device;
import com.oldwei.isup.model.provisioning.FaceSyncRequest;
import com.oldwei.isup.model.provisioning.ProvisioningAccess;
import com.oldwei.isup.model.provisioning.ProvisioningEmployee;
import com.oldwei.isup.model.provisioning.ProvisioningPhoto;
import com.oldwei.isup.model.provisioning.ProvisioningResponse;
import com.oldwei.isup.model.provisioning.ProvisioningStatus;
import com.oldwei.isup.model.provisioning.UserVerificationResponse;
import com.oldwei.isup.model.provisioning.UserDeleteRequest;
import com.oldwei.isup.model.provisioning.UserSyncRequest;
import com.oldwei.isup.sdk.service.impl.CmsUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class HikvisionProvisioningService {

    private static final String SET_UP_USER_URL = "PUT /ISAPI/AccessControl/UserInfo/SetUp?format=json";
    private static final String SEARCH_USER_URL = "POST /ISAPI/AccessControl/UserInfo/Search?format=json";
    private static final String JPEG_CONTENT_TYPE = "image/jpeg";
    private static final String DEFAULT_BEGIN_TIME = "2020-01-01T00:00:00";
    private static final String DEFAULT_END_TIME = "2037-12-31T23:59:59";
    private static final int FACE_PASSTHROUGH_TIMEOUT_MS = 10000;
    /**
     * Upper bound on the number of characters of a face-upload device
     * {@code rawResponse} we print at INFO. The device error bodies are tiny
     * (a few hundred bytes, no embedded photo bytes), so we cap generously
     * for debuggability while keeping log lines bounded.
     */
    private static final int FACE_RAW_RESPONSE_LOG_LIMIT = 4096;

    // ---- Structured photo-error codes (stable contract for PHP) ----
    /** Device already has a face for this {@code FPID} / {@code employeeNo}. */
    static final String PHOTO_ERR_FACE_ALREADY_EXISTS = "FACE_ALREADY_EXISTS";
    /** Device rejected the photo after downloading it (quality/modeling failure). */
    static final String PHOTO_ERR_REJECTED = "PHOTO_REJECTED_BY_DEVICE";
    /** Transport-level failure (SDK error / no response / timeout). */
    static final String PHOTO_ERR_TRANSPORT = "PHOTO_TRANSPORT_FAILED";
    /** Caller did not provide a photo, so no enrollment was attempted. */
    static final String PHOTO_ERR_NONE_PROVIDED = "NO_PHOTO_PROVIDED";
    /** Device returned a non-success JSON that we don't recognize as one of the above. */
    static final String PHOTO_ERR_UNKNOWN = "PHOTO_UNKNOWN_ERROR";

    // ---- Raw device tokens we pattern-match on (substrings, case-insensitive) ----
    private static final String DEVICE_TOKEN_ALREADY_EXIST = "deviceUserAlreadyExistFace";
    private static final String DEVICE_TOKEN_MODELING_ERROR = "SubpicAnalysisModelingError";
    /**
     * Cap on how many ASCII characters of the multipart preamble we log so we
     * can confirm part ordering / field names without ever dumping the JPEG
     * bytes. Stops at the first {@code \r\n\r\n} sequence that starts the
     * binary {@code FaceImage} part.
     */
    private static final int MULTIPART_PREAMBLE_LOG_LIMIT = 512;

    private final CmsUtil cmsUtil;
    private final FaceImageNormalizer faceImageNormalizer;
    private final HikProvisioningProperties provisioningProperties;
    private final FaceUrlStore faceUrlStore;

    public ProvisioningResponse syncUser(Device device, String employeeNo, UserSyncRequest request) {
        log.info("Hikvision user sync requested: deviceId={}, employeeNo={}, correlationId={}",
                device.getDeviceId(), employeeNo, request.getCorrelationId());

        String payload = buildUserInfoPayload(employeeNo, request);
        CmsUtil.IsapiPassThroughResult result = cmsUtil.passThroughWithStatus(
                device.getLoginId(),
                SET_UP_USER_URL,
                payload
        );

        boolean userSuccess = result.isSuccess()
                && StringUtils.isBlank(result.getSdkError())
                && isSuccessfulIsapiResponse(result.getRawResponse());

        // Photo-absent branch. Per the contract, when the caller sends NO
        // photo we MUST surface a structured NO_PHOTO_PROVIDED code rather than
        // silently pretending the photo step succeeded. PHP normally should
        // not even call this endpoint for a user with no photo, but the bridge
        // has to be explicit anyway.
        boolean hasPhoto = request.getPhoto() != null
                && StringUtils.isNotBlank(request.getPhoto().getContentBase64());
        if (!userSuccess) {
            // User setup itself failed - critical, HTTP 500.
            return new ProvisioningResponse(
                    request.getCorrelationId(),
                    device.getDeviceId(),
                    employeeNo,
                    false,
                    false,
                    false,
                    ProvisioningStatus.FAILED,
                    result.getRawResponse(),
                    result.getSdkError()
            );
        }
        if (!hasPhoto) {
            // User synced, but there is nothing to enroll. PARTIAL (HTTP 200)
            // so PHP can branch on photoErrorCode and decide whether to retry.
            log.info("Hikvision user sync completed without photo (NO_PHOTO_PROVIDED): deviceId={}, employeeNo={}, correlationId={}",
                    device.getDeviceId(), employeeNo, request.getCorrelationId());
            return new ProvisioningResponse(
                    request.getCorrelationId(),
                    device.getDeviceId(),
                    employeeNo,
                    true,
                    false,
                    false,
                    ProvisioningStatus.PARTIAL,
                    result.getRawResponse(),
                    null,
                    PHOTO_ERR_NONE_PROVIDED,
                    null
            );
        }

        FaceUploadResult faceResult = uploadFace(device, employeeNo, request.getPhoto());
        if (faceResult.success()) {
            return new ProvisioningResponse(
                    request.getCorrelationId(),
                    device.getDeviceId(),
                    employeeNo,
                    true,
                    true,
                    false,
                    ProvisioningStatus.SYNCED,
                    combinedRawResponse(result.getRawResponse(), faceResult.rawResponse()),
                    faceResult.sdkError(),
                    null,
                    null
            );
        }
        // User was synced, photo failed. Per contract this is NOT a 500:
        // the access-control user is usable, the photo just needs a separate
        // retry. Return PARTIAL with the structured photo-error code so the
        // PHP side can route face-only re-syncs.
        log.info("Hikvision user sync completed with photo warning (PARTIAL): deviceId={}, employeeNo={}, correlationId={}, photoErrorCode={}, photoSubStatusCode={}",
                device.getDeviceId(), employeeNo, request.getCorrelationId(),
                faceResult.photoErrorCode(), faceResult.photoSubStatusCode());
        return new ProvisioningResponse(
                request.getCorrelationId(),
                device.getDeviceId(),
                employeeNo,
                true,
                false,
                false,
                ProvisioningStatus.PARTIAL,
                combinedRawResponse(result.getRawResponse(), faceResult.rawResponse()),
                faceResult.sdkError(),
                faceResult.photoErrorCode(),
                faceResult.photoSubStatusCode()
        );
    }

    public ProvisioningResponse syncFace(Device device, String employeeNo, FaceSyncRequest request) {
        log.info("Hikvision face sync requested: deviceId={}, employeeNo={}, correlationId={}",
                device.getDeviceId(), employeeNo, request.getCorrelationId());

        FaceUploadResult result = uploadFace(device, employeeNo, request.getPhoto());
        if (result.success()) {
            return new ProvisioningResponse(
                    request.getCorrelationId(),
                    device.getDeviceId(),
                    employeeNo,
                    false,
                    true,
                    false,
                    ProvisioningStatus.SYNCED,
                    result.rawResponse(),
                    result.sdkError(),
                    null,
                    null
            );
        }
        // Face-only endpoint: photo failure is still PARTIAL-from-the-callers
        // POV when the device transport was healthy (the device simply
        // rejected the photo). A pure transport failure routes to FAILED so
        // PHP can retry the whole call. photoErrorCode distinguishes the two.
        boolean transportFailed = PHOTO_ERR_TRANSPORT.equals(result.photoErrorCode());
        return new ProvisioningResponse(
                request.getCorrelationId(),
                device.getDeviceId(),
                employeeNo,
                false,
                false,
                false,
                transportFailed ? ProvisioningStatus.FAILED : ProvisioningStatus.PARTIAL,
                result.rawResponse(),
                result.sdkError(),
                result.photoErrorCode(),
                result.photoSubStatusCode()
        );
    }

    public ProvisioningResponse deleteUser(Device device, String employeeNo, UserDeleteRequest request) {
        log.info("Hikvision user delete requested: deviceId={}, employeeNo={}, correlationId={}",
                device.getDeviceId(), employeeNo, request.getCorrelationId());

        // TODO: Implement ISUP-session-backed ISAPI user delete.
        return new ProvisioningResponse(
                request.getCorrelationId(),
                device.getDeviceId(),
                employeeNo,
                false,
                false,
                false,
                ProvisioningStatus.NOT_IMPLEMENTED,
                "",
                null
        );
    }

    public UserVerificationResponse verifyUser(Device device, String employeeNo) {
        log.info("Hikvision user verification requested: deviceId={}, employeeNo={}",
                device.getDeviceId(), employeeNo);

        CmsUtil.IsapiPassThroughResult result = cmsUtil.passThroughWithStatus(
                device.getLoginId(),
                SEARCH_USER_URL,
                buildUserSearchPayload(employeeNo)
        );

        boolean transportSuccess = result.isSuccess()
                && StringUtils.isBlank(result.getSdkError())
                && StringUtils.isNotBlank(result.getRawResponse());
        boolean found = transportSuccess && userSearchContainsEmployee(result.getRawResponse(), employeeNo);
        String bridgeStatus = transportSuccess
                ? (found ? ProvisioningStatus.SYNCED : ProvisioningStatus.NOT_FOUND)
                : ProvisioningStatus.FAILED;
        return new UserVerificationResponse(
                device.getDeviceId(),
                employeeNo,
                found,
                bridgeStatus,
                result.getRawResponse(),
                result.getSdkError()
        );
    }

    private String buildUserInfoPayload(String employeeNo, UserSyncRequest request) {
        ProvisioningEmployee employee = request.getEmployee();
        ProvisioningAccess access = request.getAccess();

        Map<String, Object> valid = new LinkedHashMap<>();
        valid.put("enable", true);
        valid.put("beginTime", valueOrDefault(access != null ? access.getBeginTime() : null, DEFAULT_BEGIN_TIME));
        valid.put("endTime", valueOrDefault(access != null ? access.getEndTime() : null, DEFAULT_END_TIME));
        valid.put("timeType", "local");

        Map<String, Object> rightPlan = new LinkedHashMap<>();
        rightPlan.put("doorNo", 1);
        rightPlan.put("planTemplateNo", valueOrDefault(access != null ? access.getPlanTemplateNo() : null, "1"));

        Map<String, Object> userInfo = new LinkedHashMap<>();
        userInfo.put("employeeNo", employeeNo);
        userInfo.put("name", valueOrDefault(employee != null ? employee.getName() : null, employeeNo));
        userInfo.put("userType", valueOrDefault(access != null ? access.getUserType() : null, "normal"));
        userInfo.put("Valid", valid);
        userInfo.put("doorRight", valueOrDefault(access != null ? access.getDoorRight() : null, "1"));
        userInfo.put("RightPlan", List.of(rightPlan));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("UserInfo", userInfo);

        return JSON.toJSONString(payload);
    }

    private String buildUserSearchPayload(String employeeNo) {
        Map<String, Object> employeeNoItem = new LinkedHashMap<>();
        employeeNoItem.put("employeeNo", employeeNo);

        Map<String, Object> condition = new LinkedHashMap<>();
        condition.put("searchID", UUID.randomUUID().toString());
        condition.put("searchResultPosition", 0);
        condition.put("maxResults", 1);
        condition.put("EmployeeNoList", List.of(employeeNoItem));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("UserInfoSearchCond", condition);

        return JSON.toJSONString(payload);
    }

    public String validatePhoto(ProvisioningPhoto photo) {
        if (photo == null) {
            return "photo is required.";
        }
        if (!StringUtils.equalsIgnoreCase(JPEG_CONTENT_TYPE, StringUtils.trim(photo.getContentType()))) {
            return "photo.contentType must be image/jpeg.";
        }
        if (StringUtils.isBlank(photo.getContentBase64())) {
            return "photo.contentBase64 is required.";
        }
        try {
            byte[] bytes = decodePhoto(photo);
            if (bytes.length == 0) {
                return "photo.contentBase64 decoded bytes must be non-empty.";
            }
        } catch (IllegalArgumentException e) {
            return "photo.contentBase64 must be valid base64.";
        }
        return null;
    }

    private FaceUploadResult uploadFace(Device device, String employeeNo, ProvisioningPhoto photo) {
        String validationError = validatePhoto(photo);
        if (validationError != null) {
            log.warn("Face upload rejected by validation: deviceId={}, employeeNo={}, reason={}",
                    device.getDeviceId(), employeeNo, validationError);
            return FaceUploadResult.failure("", validationError, PHOTO_ERR_UNKNOWN, validationError);
        }

        byte[] originalBytes = decodePhoto(photo);
        FaceImageNormalizer.NormalizedFace face;
        try {
            face = faceImageNormalizer.normalize(originalBytes);
        } catch (RuntimeException e) {
            // Some readers (rare on the bundled JRE) refuse a perfectly valid
            // JPEG. Fall back to the original bytes so the device gets a chance
            // to validate by itself, instead of failing before the SDK call.
            log.warn("Face image normalization failed, falling back to original bytes: deviceId={}, employeeNo={}, bytes={}, error={}",
                    device.getDeviceId(), employeeNo, originalBytes.length, e.getMessage());
            face = new FaceImageNormalizer.NormalizedFace(
                    0, 0, 0, 0,
                    originalBytes.length,
                    originalBytes.length,
                    FaceImageNormalizer.isProgressiveJpeg(originalBytes),
                    FaceImageNormalizer.isProgressiveJpeg(originalBytes),
                    originalBytes
            );
        }

        FaceUploadMode mode = FaceUploadMode.fromConfig(provisioningProperties.getFaceUploadMode());

        // First attempt: try to create the face record as-is. This is the
        // happy path for a brand-new employeeNo.
        FaceUploadResult upload = mode.isFaceUrlMode()
                ? uploadFaceByUrl(device, employeeNo, face, mode)
                : uploadFaceByMultipart(device, employeeNo, face, mode);
        if (upload.success()) {
            return upload;
        }

        // ---- Facial upsert path ----
        // The most common reason a create fails on an already-provisioned
        // employeeNo is that the device already holds a face for this FPID
        // and returns `deviceUserAlreadyExistFace`. The Hikvision FDLib API
        // is NOT idempotent on POST, so we have to evict the existing face
        // for (FDID, FPID) via the DS-K1Txxxx-correct
        // `PUT /ISAPI/Intelligent/FDLib/FDSearch/Delete` path and re-POST
        // once. Bounded to a single retry - no infinite loop.
        if (PHOTO_ERR_FACE_ALREADY_EXISTS.equals(upload.photoErrorCode())) {
            String fdid = provisioningProperties.getFdid();
            log.info("Face already exists for employeeNo; attempting delete+recreate: deviceId={}, employeeNo={}, FDID={}, FPID={}",
                    device.getDeviceId(), employeeNo, fdid, employeeNo);

            FaceDeleteResult delete = deleteExistingFace(device, fdid, employeeNo);
            // Only re-POST when the device explicitly confirmed the delete
            // (statusCode=1 / subStatusCode=ok). A bare transportOk is NOT
            // enough: the DS-K1T321MFWX answered the old DELETE verb with
            // HTTP-200-but-body statusCode=4 `notSupport`, and re-POSTing
            // immediately would reproduce the same `deviceUserAlreadyExistFace`.
            // Requiring deviceOk both matches the ISAPI contract and keeps the
            // retry bounded to ONE attempt for any device that refuses the
            // delete path.
            if (delete.deviceOk()) {
                // Device confirmed the FPID is gone - re-enroll the same bytes.
                FaceUploadResult retry = mode.isFaceUrlMode()
                        ? uploadFaceByUrl(device, employeeNo, face, mode)
                        : uploadFaceByMultipart(device, employeeNo, face, mode);
                if (retry.success()) {
                    log.info("Face upsert succeeded on retry: deviceId={}, employeeNo={}, deleteDeviceOk={}, deleteStatusCode={}, deleteRawResponse={}",
                            device.getDeviceId(), employeeNo, delete.deviceOk(), delete.statusCode(), truncate(delete.rawResponse()));
                    return retry;
                }
                // Retry still failed - report the retry's error code, but note
                // that an upsert was attempted (carry the original + delete +
                // retry raw responses so PHP can diagnose).
                return retry.withReattempted(upload.rawResponse(), delete);
            }
            // Delete was not supported by the firmware (statusCode=4 /
            // notSupport) or failed at the transport layer. Do NOT re-POST -
            // the existing face is still there and FaceDataRecord would just
            // return `deviceUserAlreadyExistFace` again. Still return PARTIAL
            // with photoErrorCode=FACE_ALREADY_EXISTS /
            // photoSubStatusCode=deviceUserAlreadyExistFace (from the ORIGINAL
            // POST), but ALSO splice the delete rejection into the final
            // rawResponse so PHP sees BOTH the POST body and the DELETE
            // notSupport body without a second call. Useful for an operator
            // who wants to chase this in the device admin UI.
            log.warn("Face upsert aborted: face-delete did not confirm: deviceId={}, employeeNo={}, transportOk={}, deviceOk={}, statusCode={}, statusString={}, subStatusCode={}, errorCode={}, errorMsg={}, sdkError={}, rawResponse={}",
                    device.getDeviceId(), employeeNo,
                    delete.transportOk(), delete.deviceOk(),
                    delete.statusCode(), delete.statusString(), delete.subStatusCode(),
                    delete.errorCode(), delete.errorMsg(),
                    delete.sdkError(), truncate(delete.rawResponse()));

            // Splice the delete-rejection details onto the upload result so
            // the composed rawResponse survives all the way to Laravel.
            JSONObject deleteStatus = new JSONObject();
            deleteStatus.put("statusCode", delete.statusCode());
            deleteStatus.put("statusString", delete.statusString());
            deleteStatus.put("subStatusCode", delete.subStatusCode());
            deleteStatus.put("errorCode", delete.errorCode());
            deleteStatus.put("errorMsg", delete.errorMsg());
            deleteStatus.put("deviceOk", delete.deviceOk());
            deleteStatus.put("transportOk", delete.transportOk());
            deleteStatus.put("sdkError", delete.sdkError());
            deleteStatus.put("rawResponse", delete.rawResponse());
            JSONObject composite = new JSONObject();
            composite.put("originalFaceDataRecordRawResponse", upload.rawResponse());
            composite.put("deleteStatus", deleteStatus);
            upload = new FaceUploadResult(
                    upload.success(),
                    composite.toJSONString(),
                    upload.sdkError(),
                    // Keep the ORIGINAL POST error code/subCode so PHP branches
                    // on FACE_ALREADY_EXISTS first; `deleteStatus` exposes the
                    // notSupport details for human diagnosis.
                    upload.photoErrorCode(),
                    upload.photoSubStatusCode()
            );
        }

        // SubpicAnalysisModelingError / any other device-rejected (or pure
        // transport) failure: do NOT retry. Hikvision could not model the
        // photo; retrying the same bytes would fail identically. Report the
        // structured photo error as-is.
        return upload;
    }

    /**
     * URL-based enrollment. The bridge publishes the normalized JPEG at a
     * temporary unguessable internal URL (see {@link FaceUrlStore}) and sends
     * a JSON-only request carrying that URL in the Hikvision {@code faceUrl}
     * field. The device then fetches the JPEG over HTTP just like iVMS-4200
     * "add by URL". This is the escape hatch for firmware that rejects the
     * binary multipart path with {@code badJsonFormat}.
     */
    private FaceUploadResult uploadFaceByUrl(Device device, String employeeNo,
                                             FaceImageNormalizer.NormalizedFace face, FaceUploadMode mode) {
        String faceUrl = faceUrlStore.publish(employeeNo, face.bytes());
        // Resolve shape + faceLibType + FDID from config so an operator can
        // chase firmware idiosyncrasies (e.g. faceURL vs faceUrl, wrapped vs
        // flat, blackFD vs blackFace) without a rebuild. shapeOverride comes
        // from FLOW_HIK_FACE_URL_SHAPE and may null-out if unset; the mode
        // supplies a sensible default in that case.
        FaceUrlShape shapeOverride = FaceUrlShape.fromConfig(provisioningProperties.getFaceUrlShape());
        String faceLibType = provisioningProperties.getFaceLibType();
        String fdid = provisioningProperties.getFdid();
        String payload = mode.faceRecordJsonUrl(employeeNo, faceUrl, shapeOverride, faceLibType, fdid);
        byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
        String url = mode.method() + " " + mode.isapiPath() + "?format=json";

        // Sanitized log: NEVER print faceUrl value or bytes, only structural
        // info to confirm the right shape is on the wire.
        log.info("Face upload (URL mode) prepared: deviceId={}, employeeNo={}, mode={}, shape={}, faceLibType={}, FDID={}, FPID={}, urlKey={}, payloadHasFaceURL={}, payloadHasFaceUrl={}, payloadBytes={}, isapiUrl={}",
                device.getDeviceId(),
                employeeNo,
                mode.name(),
                shapeOverride.name(),
                faceLibType,
                fdid,
                employeeNo,
                shapeOverride.urlKey(),
                payload.contains("\"faceURL\""),
                payload.contains("\"faceUrl\""),
                payloadBytes.length,
                url);

        CmsUtil.IsapiPassThroughResult result = cmsUtil.passThroughBytesWithStatus(
                device.getLoginId(),
                url,
                payloadBytes,
                FACE_PASSTHROUGH_TIMEOUT_MS
        );

        boolean transportOk = result.isSuccess() && StringUtils.isBlank(result.getSdkError());
        String raw = result.getRawResponse();
        boolean success = transportOk && isSuccessfulIsapiResponse(raw);

        String[] photoErr = resolvePhotoError(transportOk, raw, result.getSdkError());

        // Always revoke the temporary URL on completion - success (one-shot)
        // and failure (don't leave the byte buffer queryable). The upsert
        // retry path publishes a FRESH token and calls here again, so this
        // global clear is safe: every publish→POST→clear cycle is serial.
        faceUrlStore.clear();

        // Log the FULL raw response so device-level rejections (e.g.
        // deviceUserAlreadyExistFace, SubpicAnalysisModelingError) are no
        // longer hidden behind a bare length number. The body is pure JSON
        // error text (no embedded JPEG bytes), so dumping it is safe.
        log.info("Face upload (URL mode) result: deviceId={}, employeeNo={}, url={}, success={}, transportOk={}, sdkError={}, photoErrorCode={}, photoSubStatusCode={}, rawResponseLength={}, rawResponse={}",
                device.getDeviceId(),
                employeeNo,
                url,
                success,
                transportOk,
                result.getSdkError(),
                photoErr[0],
                photoErr[1],
                raw == null ? 0 : raw.length(),
                truncate(raw));

        return new FaceUploadResult(success, raw, result.getSdkError(), photoErr[0], photoErr[1]);
    }

    private FaceUploadResult uploadFaceByMultipart(Device device, String employeeNo,
                                                   FaceImageNormalizer.NormalizedFace face, FaceUploadMode mode) {
        // Guzzle-style boundary: a plain ASCII token like "flowhikface<uuid>".
        // The leading "--" delimiters are written separately in the body per
        // RFC 2046 - the boundary value itself carries no dashes, matching
        // what Laravel's Http::attach (Guzzle MultipartStream) emits and what
        // the Hikvision device parser expects in the &boundary= URL query.
        String boundary = "flowhikface" + UUID.randomUUID().toString().replace("-", "");
        byte[] multipartBody = buildFaceMultipartBody(employeeNo, face.bytes(), boundary, mode);
        String url = buildFaceUrl(boundary, mode);

        log.info("Face upload prepared: deviceId={}, employeeNo={}, mode={}, faceDataRecordJson={}, originalBytes={}, originalProgressive={}, normalizedBytes={}, normalizedProgressive={}, srcSize={}, normalizedSize={}, multipartBytes={}, contentType=multipart/form-data, boundary={}, isapiUrl={}, imageFieldName={}, multipartPreamble={}",
                device.getDeviceId(),
                employeeNo,
                mode.name(),
                mode.faceRecordJson(employeeNo, null),
                face.originalBytes(),
                face.originalProgressive(),
                face.normalizedBytes(),
                face.normalizedProgressive(),
                face.srcWidth() + "x" + face.srcHeight(),
                face.outWidth() + "x" + face.outHeight(),
                multipartBody.length,
                boundary,
                url,
                mode.imageFieldName(),
                describeMultipartPreamble(multipartBody));

        CmsUtil.IsapiPassThroughResult result = cmsUtil.passThroughBytesWithStatus(
                device.getLoginId(),
                url,
                multipartBody,
                FACE_PASSTHROUGH_TIMEOUT_MS
        );

        boolean transportOk = result.isSuccess() && StringUtils.isBlank(result.getSdkError());
        String raw = result.getRawResponse();
        boolean success = transportOk && isSuccessfulIsapiResponse(raw);
        String[] photoErr = resolvePhotoError(transportOk, raw, result.getSdkError());

        // Full raw response log (JSON-only body, no JPEG bytes here either).
        log.info("Face upload result: deviceId={}, employeeNo={}, mode={}, url={}, success={}, transportOk={}, sdkError={}, photoErrorCode={}, photoSubStatusCode={}, rawResponseLength={}, rawResponse={}",
                device.getDeviceId(),
                employeeNo,
                mode.name(),
                url,
                success,
                transportOk,
                result.getSdkError(),
                photoErr[0],
                photoErr[1],
                raw == null ? 0 : raw.length(),
                truncate(raw));

        return new FaceUploadResult(success, raw, result.getSdkError(), photoErr[0], photoErr[1]);
    }

    private byte[] decodePhoto(ProvisioningPhoto photo) {
        return Base64.getDecoder().decode(photo.getContentBase64());
    }

    private String buildFaceUrl(String boundary, FaceUploadMode mode) {
        return mode.method() + " " + mode.isapiPath() + "?format=json&boundary=" + boundary;
    }

    private byte[] buildFaceMultipartBody(String employeeNo, byte[] imageBytes, String boundary, FaceUploadMode mode) {
        // The Hikvision multipart parser extracts the part named
        // "FaceDataRecord" and re-attaches the outer key from the JSON payload
        // we provide here. To mirror the proven direct-IP Laravel
        // implementation (HikvisionUserSyncService::sendFace via Guzzle), the
        // part body is the WRAPPED object {"FaceDataRecord":{...}}. Earlier
        // bare-object attempts were rejected with statusCode=5 / badJsonFormat
        // on the DS-K1T321MFWX, so we restored the wrapper.
        byte[] faceDataJson = mode.faceRecordJson(employeeNo, null).getBytes(StandardCharsets.UTF_8);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        // Part 1: FaceDataRecord (text JSON). Always first - Hikvision parses
        // the JSON descriptor before consuming the binary image part.
        //
        // Guzzle-style headers ONLY: Content-Disposition + Content-Type. We do
        // NOT emit per-part Content-Length or Content-Transfer-Encoding - both
        // are non-RFC for multipart/form-data and were tripping the device
        // parser into badJsonFormat.
        writeAscii(output, "--" + boundary + "\r\n");
        writeAscii(output, "Content-Disposition: form-data; name=\"FaceDataRecord\"\r\n");
        writeAscii(output, "Content-Type: application/json\r\n\r\n");
        output.writeBytes(faceDataJson);
        writeAscii(output, "\r\n");
        // Part 2: image part. Field name varies per device family (FaceImage /
        // img). Same minimal header set as part 1.
        writeAscii(output, "--" + boundary + "\r\n");
        writeAscii(output, "Content-Disposition: form-data; name=\"" + mode.imageFieldName() + "\"; filename=\"face.jpg\"\r\n");
        writeAscii(output, "Content-Type: image/jpeg\r\n\r\n");
        output.writeBytes(imageBytes);
        writeAscii(output, "\r\n");
        writeAscii(output, "--" + boundary + "--\r\n");
        return output.toByteArray();
    }

    /**
     * Renders a small, text-only preview of the multipart preamble (everything
     * up to the binary image part) so operators can confirm part ordering and
     * field names in logs. Stops before the JPEG bytes so no image data is
     * ever logged.
     */
    private String describeMultipartPreamble(byte[] multipartBody) {
        if (multipartBody == null || multipartBody.length == 0) {
            return "<empty>";
        }
        int limit = Math.min(multipartBody.length, MULTIPART_PREAMBLE_LOG_LIMIT);
        // Stop at the boundary between the two parts, just before the binary
        // image part's body, to guarantee we never include JPEG bytes.
        int imagePartStart = indexOfAscii(multipartBody, "\r\n\r\n", indexOfAscii(multipartBody, "image/jpeg", 0));
        if (imagePartStart > 0 && imagePartStart < limit) {
            limit = imagePartStart;
        }
        return new String(multipartBody, 0, limit, StandardCharsets.US_ASCII)
                .replace("\r", "\\r")
                .replace("\n", "\\n");
    }

    private int indexOfAscii(byte[] haystack, String needle, int from) {
        if (needle.isEmpty()) {
            return from;
        }
        byte[] needleBytes = needle.getBytes(StandardCharsets.US_ASCII);
        int max = haystack.length - needleBytes.length;
        for (int i = Math.max(0, from); i <= max; i++) {
            boolean match = true;
            for (int j = 0; j < needleBytes.length; j++) {
                if (haystack[i + j] != needleBytes[j]) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return i;
            }
        }
        return -1;
    }

    private void writeAscii(ByteArrayOutputStream output, String value) {
        output.writeBytes(value.getBytes(StandardCharsets.US_ASCII));
    }

    private String combinedRawResponse(String userRawResponse, String faceRawResponse) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("userRawResponse", userRawResponse);
        payload.put("faceRawResponse", faceRawResponse);
        return JSON.toJSONString(payload);
    }

    private String valueOrDefault(String value, String defaultValue) {
        return StringUtils.isNotBlank(value) ? value : defaultValue;
    }

    private boolean isSuccessfulIsapiResponse(String rawResponse) {
        if (StringUtils.isBlank(rawResponse)) {
            return false;
        }

        try {
            JSONObject json = JSON.parseObject(rawResponse);
            Integer statusCode = json.getInteger("statusCode");
            String subStatusCode = json.getString("subStatusCode");
            return Integer.valueOf(1).equals(statusCode) && StringUtils.equalsIgnoreCase("ok", subStatusCode);
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * Translates a face-upload device response into a stable two-part
     * (errorCode, subStatusCode) pair. This is the central place where the
     * bridge differentiates the two Hikvision-specific rejection reasons
     * Laravel/PHP needs to branch on:
     * <ul>
     *   <li>{@code FACE_ALREADY_EXISTS} ({@code deviceUserAlreadyExistFace}):
     *       recoverable via the upsert delete+recreate path;</li>
     *   <li>{@code PHOTO_REJECTED_BY_DEVICE} ({@code SubpicAnalysisModelingError}):
     *       NON-recoverable - the device's face-analysis module rejected the
     *       JPEG. Retrying the same bytes would fail identically, so we do NOT
     *       loop here;</li>
     *   <li>{@code PHOTO_TRANSPORT_FAILED}: SDK-level failure (timeout / no
     *       login / NET_ECMS error). Routes to {@link ProvisioningStatus#FAILED}
     *       so the caller can retry the whole provisioning call;</li>
     *   <li>{@code PHOTO_UNKNOWN_ERROR}: device returned a non-success JSON we
     *       don't recognize. Surfaces the raw body for triage.</li>
     * </ul>
     *
     * <p>Returns a 2-element String array: {@code [errorCode, subStatusCode]}.
     * Both entries are {@code null} only when {@code transportOk && success}
     * (i.e. nothing went wrong); the caller may also pass success in via the
     * absence of a known pattern.
     *
     * @param transportOk did the SDK call itself succeed (no SDK error / !success flag)?
     * @param raw         the device's raw JSON body
     * @param sdkError    SDK error token from {@link CmsUtil.IsapiPassThroughResult#getSdkError()}
     */
    private String[] resolvePhotoError(boolean transportOk, String raw, String sdkError) {
        // Pure transport failure - no device body to interpret.
        if (!transportOk) {
            return new String[]{PHOTO_ERR_TRANSPORT, sdkError};
        }
        // Device answered. Walk the known rejection patterns in priority order.
        if (StringUtils.containsIgnoreCase(raw, DEVICE_TOKEN_ALREADY_EXIST)) {
            // Pull the exact device token (verbatim case) out for diagnostics.
            return new String[]{PHOTO_ERR_FACE_ALREADY_EXISTS, DEVICE_TOKEN_ALREADY_EXIST};
        }
        if (StringUtils.containsIgnoreCase(raw, DEVICE_TOKEN_MODELING_ERROR)) {
            return new String[]{PHOTO_ERR_REJECTED, DEVICE_TOKEN_MODELING_ERROR};
        }
        // Try to harvest the device's own statusCode / subStatusCode so the
        // PHP side still gets a meaningful sub-code even for novel rejections.
        String sub = extractDeviceSubStatusCode(raw);
        return new String[]{PHOTO_ERR_UNKNOWN, sub != null ? sub : truncate(raw)};
    }

    private String extractDeviceSubStatusCode(String raw) {
        if (StringUtils.isBlank(raw)) {
            return null;
        }
        try {
            JSONObject json = JSON.parseObject(raw);
            Object sub = json.get("subStatusCode");
            return sub != null ? String.valueOf(sub) : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * Bounds a raw-response string for log lines / payload fields. The
     * Hikvision face-API error bodies are tiny (the {@code rawResponseLength=158}
     * from the field log confirms this), so the limit caps pathological
     * responses without trimming anything realistic.
     */
    private String truncate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= FACE_RAW_RESPONSE_LOG_LIMIT
                ? value
                : value.substring(0, FACE_RAW_RESPONSE_LOG_LIMIT) + "...(truncated)";
    }

    /**
     * Evicts an existing face record from the configured FDLib so a follow-up
     * {@code POST /ISAPI/Intelligent/FDLib/FaceDataRecord} can re-enroll the
     * same {@code FPID}. Called by the {@code uploadFace} upsert path when a
     * create returns {@code deviceUserAlreadyExistFace}.
     *
     * <p><b>Why PUT FDSearch/Delete and not {@code DELETE /FDLib?FDID&FPID}:</b>
     * the DS-K1T321MFWX rejects the bare-DELETE verb with HTTP-200-but-body
     * {@code statusCode=4 / statusString="Invalid Operation" /
     * subStatusCode="notSupport" / errorCode=1073741825}. The firmware's real
     * per-FPID removal path is the search-delete form documented for the
     * DS-K1Txxxx access-control family:
     * <pre>{@code
     *   PUT /ISAPI/Intelligent/FDLib/FDSearch/Delete?format=json&FDID={fdid}&faceLibType={faceLibType}
     *   Content-Type: application/json
     *   {"FPID":[{"value":"<employeeNo>"}]}
     * }</pre>
     * A device-confirmed delete returns {@code statusCode=1 /
     * subStatusCode="ok"}; only that shape gates the re-POST (see
     * {@link FaceDeleteResult#deviceOk()}), so a {@code notSupport} response
     * on the PUT (or any other rejection) leaves the original face untouched
     * instead of cycling the POST.
     *
     * @param fdid FDLib id (default "1", from {@code hik.provisioning.fdid})
     * @param fpid face id (= employeeNo, bound to the access-control user)
     */
    private FaceDeleteResult deleteExistingFace(Device device, String fdid, String fpid) {
        String faceLibType = provisioningProperties.getFaceLibType();
        String url = "PUT /ISAPI/Intelligent/FDLib/FDSearch/Delete?format=json"
                + "&FDID=" + fdid + "&faceLibType=" + faceLibType;
        // ISAPI contract for DS-K1Txxxx: an array of single-key {"value":..}
        // objects under "FPID". One FPID per call keeps the retry targeted to
        // the employeeNo we are upserting.
        JSONObject fpidItem = new JSONObject();
        fpidItem.put("value", fpid);
        JSONArray fpidList = new JSONArray();
        fpidList.add(fpidItem);
        JSONObject body = new JSONObject();
        body.put("FPID", fpidList);
        byte[] bodyBytes = body.toJSONString().getBytes(StandardCharsets.UTF_8);

        CmsUtil.IsapiPassThroughResult result = cmsUtil.passThroughBytesWithStatus(
                device.getLoginId(),
                url,
                bodyBytes,
                FACE_PASSTHROUGH_TIMEOUT_MS
        );
        String raw = result.getRawResponse();
        boolean transportOk = result.isSuccess() && StringUtils.isBlank(result.getSdkError());
        Integer statusCode = null;
        String statusString = null;
        String subStatusCode = null;
        String errorCode = null;
        String errorMsg = null;
        boolean deviceOk = false;
        if (transportOk && StringUtils.isNotBlank(raw)) {
            try {
                JSONObject resp = JSON.parseObject(raw);
                statusCode = resp.getInteger("statusCode");
                statusString = resp.getString("statusString");
                subStatusCode = resp.getString("subStatusCode");
                errorCode = resp.getString("errorCode");
                errorMsg = resp.getString("errorMsg");
                // Mirror the upload-path success gate for cross-shape parity.
                deviceOk = Integer.valueOf(1).equals(statusCode)
                        && StringUtils.equalsIgnoreCase("ok", subStatusCode);
            } catch (Exception parseEx) {
                // Body was not JSON - cannot confirm device-level success.
                log.warn("Face delete-by-FPID response was not parseable JSON: deviceId={}, fpid={}, rawResponse={}",
                        device.getDeviceId(), fpid, truncate(raw));
            }
        }

        log.info("Face delete-by-FPID: deviceId={}, fdid={}, faceLibType={}, fpid={}, method=PUT, url={}, body={}, transportOk={}, deviceOk={}, statusCode={}, statusString={}, subStatusCode={}, errorCode={}, errorMsg={}, sdkError={}, rawResponse={}",
                device.getDeviceId(), fdid, faceLibType, fpid, url,
                body.toJSONString(),
                transportOk, deviceOk,
                statusCode, statusString, subStatusCode, errorCode, errorMsg,
                result.getSdkError(),
                truncate(raw));
        return new FaceDeleteResult(transportOk, deviceOk, raw, result.getSdkError(),
                statusCode, statusString, subStatusCode, errorCode, errorMsg);
    }

    /**
     * Outcome of a {@link #deleteExistingFace} call. Carries both transport
     * health ( {@code transportOk} = SDK call returned without error) and the
     * device-level delete confirmation ( {@code deviceOk} =
     * statusCode==1 &amp;&amp; subStatusCode=="ok"). Only {@code deviceOk}
     * authorizes the re-POST, because some DS-K1Txxxx firmware returns HTTP 200
     * with a body of {@code statusCode=4 / notSupport} for verb mismatches;
     * treating that as success would re-fire FaceDataRecord against the stale
     * FPID and reproduce {@code deviceUserAlreadyExistFace}.
     */
    private record FaceDeleteResult(boolean transportOk,
                                    boolean deviceOk,
                                    String rawResponse,
                                    String sdkError,
                                    Integer statusCode,
                                    String statusString,
                                    String subStatusCode,
                                    String errorCode,
                                    String errorMsg) {
    }

    private boolean userSearchContainsEmployee(String rawResponse, String employeeNo) {
        if (StringUtils.isBlank(rawResponse)) {
            return false;
        }

        try {
            JSONObject root = JSON.parseObject(rawResponse);
            JSONObject userInfoSearch = root.getJSONObject("UserInfoSearch");
            if (userInfoSearch == null) {
                return false;
            }

            Object userInfo = userInfoSearch.get("UserInfo");
            if (userInfo instanceof JSONArray users) {
                return users.stream().anyMatch(user -> userInfoMatchesEmployee(user, employeeNo));
            }

            return userInfoMatchesEmployee(userInfo, employeeNo);
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean userInfoMatchesEmployee(Object userInfo, String employeeNo) {
        if (!(userInfo instanceof JSONObject user)) {
            return false;
        }

        Object rawEmployeeNo = user.get("employeeNo");
        return rawEmployeeNo != null && StringUtils.equals(String.valueOf(rawEmployeeNo).trim(), employeeNo.trim());
    }

    /**
     * Outcome of a face-enrollment attempt. {@code photoErrorCode} /
     * {@code photoSubStatusCode} are populated on every non-success finish so
     * the service can route {@link ProvisioningStatus#PARTIAL} vs
     * {@link ProvisioningStatus#FAILED} correctly and Laravel gets a stable
     * code to branch on. They are {@code null} only when {@code success=true}.
     *
     * @param success           did the device accept the photo?
     * @param rawResponse       full device JSON body (kept verbatim, may be large)
     * @param sdkError          SDK-level error token, or {@code null}
     * @param photoErrorCode    one of {@code PHOTO_ERR_*} constants, or {@code null} when success
     * @param photoSubStatusCode raw device token behind the error (e.g.
     *                          {@code deviceUserAlreadyExistFace}), or {@code null} when success
     */
    private record FaceUploadResult(boolean success,
                                    String rawResponse,
                                    String sdkError,
                                    String photoErrorCode,
                                    String photoSubStatusCode) {

        /** Convenience for the no-photo / validation-rejection early returns. */
        static FaceUploadResult failure(String rawResponse, String sdkError,
                                        String photoErrorCode, String photoSubStatusCode) {
            return new FaceUploadResult(false, rawResponse, sdkError, photoErrorCode, photoSubStatusCode);
        }

        /**
         * Builds a result that signals "the face upload ultimately FAILED, but
         * an upsert was attempted on the way here". The retry's own error
         * code wins (it's the most recent), and {@code rawResponse} is wrapped
         * so an operator can see the original POST rejection, the confirmed
         * FPID delete result, and the retry POST response in one JSON object.
         * The structured delete fields (statusCode/statusString/
         * subStatusCode/errorCode/errorMsg) are surfaced in a sibling
         * `deleteStatus` sub-object so PHP can branch on, say, a `notSupport`
         * delete without re-parsing the raw bodies.
         */
        FaceUploadResult withReattempted(String firstAttemptRaw, FaceDeleteResult deleteResult) {
            JSONObject deleteStatus = new JSONObject();
            deleteStatus.put("statusCode", deleteResult.statusCode());
            deleteStatus.put("statusString", deleteResult.statusString());
            deleteStatus.put("subStatusCode", deleteResult.subStatusCode());
            deleteStatus.put("errorCode", deleteResult.errorCode());
            deleteStatus.put("errorMsg", deleteResult.errorMsg());
            deleteStatus.put("deviceOk", deleteResult.deviceOk());

            JSONObject composedJson = new JSONObject();
            composedJson.put("firstAttemptRawResponse", firstAttemptRaw);
            composedJson.put("deleteRawResponse", deleteResult.rawResponse());
            composedJson.put("deleteStatus", deleteStatus);
            composedJson.put("retryRawResponse", this.rawResponse);
            return new FaceUploadResult(
                    false,
                    composedJson.toJSONString(),
                    this.sdkError,
                    this.photoErrorCode,
                    this.photoSubStatusCode
            );
        }
    }
}
