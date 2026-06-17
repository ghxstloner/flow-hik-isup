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

        if (!userSuccess || request.getPhoto() == null || StringUtils.isBlank(request.getPhoto().getContentBase64())) {
            return new ProvisioningResponse(
                    request.getCorrelationId(),
                    device.getDeviceId(),
                    employeeNo,
                    userSuccess,
                    false,
                    false,
                    userSuccess ? ProvisioningStatus.SYNCED : ProvisioningStatus.FAILED,
                    result.getRawResponse(),
                    result.getSdkError()
            );
        }

        FaceUploadResult faceResult = uploadFace(device, employeeNo, request.getPhoto());
        boolean success = faceResult.success();
        return new ProvisioningResponse(
                request.getCorrelationId(),
                device.getDeviceId(),
                employeeNo,
                true,
                success,
                false,
                success ? ProvisioningStatus.SYNCED : ProvisioningStatus.FAILED,
                combinedRawResponse(result.getRawResponse(), faceResult.rawResponse()),
                faceResult.sdkError()
        );
    }

    public ProvisioningResponse syncFace(Device device, String employeeNo, FaceSyncRequest request) {
        log.info("Hikvision face sync requested: deviceId={}, employeeNo={}, correlationId={}",
                device.getDeviceId(), employeeNo, request.getCorrelationId());

        FaceUploadResult result = uploadFace(device, employeeNo, request.getPhoto());
        return new ProvisioningResponse(
                request.getCorrelationId(),
                device.getDeviceId(),
                employeeNo,
                false,
                result.success(),
                false,
                result.success() ? ProvisioningStatus.SYNCED : ProvisioningStatus.FAILED,
                result.rawResponse(),
                result.sdkError()
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
            return new FaceUploadResult(false, "", validationError);
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

        if (mode == FaceUploadMode.FACE_URL) {
            return uploadFaceByUrl(device, employeeNo, face, mode);
        }
        return uploadFaceByMultipart(device, employeeNo, face, mode);
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
        String payload = mode.faceRecordJson(employeeNo, faceUrl);
        byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
        String url = mode.method() + " " + mode.isapiPath() + "?format=json";

        log.info("Face upload (URL mode) prepared: deviceId={}, employeeNo={}, mode={}, faceUrl={}, payloadHasFaceUrl=true, payloadBytes={}, isapiUrl={}",
                device.getDeviceId(),
                employeeNo,
                mode.name(),
                faceUrl,
                payloadBytes.length,
                url);

        CmsUtil.IsapiPassThroughResult result = cmsUtil.passThroughBytesWithStatus(
                device.getLoginId(),
                url,
                payloadBytes,
                FACE_PASSTHROUGH_TIMEOUT_MS
        );

        boolean success = result.isSuccess()
                && StringUtils.isBlank(result.getSdkError())
                && isSuccessfulIsapiResponse(result.getRawResponse());

        // Always revoke the temporary URL on completion - success (one-shot)
        // and failure (don't leave the byte buffer queryable).
        faceUrlStore.clear();

        log.info("Face upload (URL mode) result: deviceId={}, employeeNo={}, url={}, success={}, transportOk={}, sdkError={}, rawResponseLength={}",
                device.getDeviceId(),
                employeeNo,
                url,
                success,
                result.isSuccess(),
                result.getSdkError(),
                result.getRawResponse() == null ? 0 : result.getRawResponse().length());

        return new FaceUploadResult(success, result.getRawResponse(), result.getSdkError());
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

        boolean success = result.isSuccess()
                && StringUtils.isBlank(result.getSdkError())
                && isSuccessfulIsapiResponse(result.getRawResponse());

        log.info("Face upload result: deviceId={}, employeeNo={}, mode={}, url={}, success={}, transportOk={}, sdkError={}, rawResponseLength={}",
                device.getDeviceId(),
                employeeNo,
                mode.name(),
                url,
                success,
                result.isSuccess(),
                result.getSdkError(),
                result.getRawResponse() == null ? 0 : result.getRawResponse().length());

        return new FaceUploadResult(success, result.getRawResponse(), result.getSdkError());
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

    private record FaceUploadResult(boolean success, String rawResponse, String sdkError) {
    }
}
