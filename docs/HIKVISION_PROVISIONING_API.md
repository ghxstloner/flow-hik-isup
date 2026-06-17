# Hikvision ISUP Provisioning API

This document defines the bridge API Laravel should call to provision Hikvision users/faces through an already registered ISUP session.

The endpoints are phase-1 bridge contracts. User provisioning and JPEG face upload are implemented through the cached ISUP session. The feature flag is disabled by default.

Current field observation:

- The first tested ISUP device appears at `GET /api/devices` as `deviceId = "1"`, `isOnline = 1`, and `loginId = 0`.
- This value may not match Laravel `hikvision_device_info.DEVICE_SERIAL`.
- If it differs, Laravel should add a separate mapping field such as `ISUP_DEVICE_ID` instead of overloading `DEVICE_ID` or assuming serial equality.

## Existing Endpoints

### List Registered Devices

```http
GET /api/devices
```

Query parameters:

- `deviceId` optional partial match.
- `isOnline` optional integer, normally `1`.

Response shape follows the bridge `R<T>` wrapper:

```json
{
  "code": 200,
  "msg": "success",
  "data": [
    {
      "deviceId": "DS-K1T...",
      "loginId": 12,
      "isOnline": 1,
      "deviceType": null,
      "channels": []
    }
  ]
}
```

### Get Registered Device

```http
GET /api/devices/{deviceId}
```

Use this as Laravel's preflight check before provisioning. `deviceId` should be the value returned by the ISUP registration callback.

## Proposed Endpoints

### Sync One User

```http
PUT /api/devices/{deviceId}/users/{employeeNo}
```

Purpose:

- Create or update one Hikvision access-control user on one online ISUP device.
- Optionally upload the face image for the same user.

Path parameters:

- `deviceId`: ISUP device ID currently stored in `DeviceCacheService`.
- `employeeNo`: Hikvision employee number. Laravel should initially send collaborator `ficha`.

Request body:

```json
{
  "correlationId": "6ec36f07-541f-4b1e-9d2b-bc1844a5c51f",
  "tenantDb": "nomina_empresa_01",
  "laravelDeviceId": 15,
  "deviceSerial": "DS-K1T...",
  "employee": {
    "personalId": 123,
    "ficha": 456,
    "employeeNo": "456",
    "name": "NOMBRE APELLIDO",
    "identification": "8-888-888"
  },
  "access": {
    "userType": "normal",
    "beginTime": "2020-01-01T00:00:00",
    "endTime": "2037-12-31T23:59:59",
    "doorRight": "1",
    "planTemplateNo": "1"
  },
  "photo": {
    "name": "8-888-888.jpg",
    "contentType": "image/jpeg",
    "contentBase64": "/9j/4AAQSkZJRgABAQ..."
  }
}
```

Field rules:

- `correlationId` is passed through logs and responses.
- `tenantDb` is informational for bridge logs; Laravel remains the tenant authority.
- `laravelDeviceId` is informational and must not be used as the ISUP lookup key.
- `deviceSerial` should match Laravel `hikvision_device_info.DEVICE_SERIAL`.
- `employee.employeeNo` must match the path `employeeNo`.
- `photo` may be omitted when only user data should be synced.

Bridge behavior:

1. Resolve `deviceId` from `DeviceCacheService`.
2. If missing or offline, return `409`.
3. Use cached `loginId` to send ISAPI user setup over the ISUP session.
4. If `photo` exists, upload face data over the ISUP session.
5. Return a structured result with user and face sync outcomes.

Current behavior:

- Requires `X-Flow-Bridge-Token`.
- Requires `hik.features.provisioning.enabled=true`.
- Validates `employeeNo` against `employee.employeeNo`.
- Uses `DeviceCacheService` to resolve bridge `deviceId` to the cached ISUP `loginId`.
- Sends user data through ISUP passthrough to `PUT /ISAPI/AccessControl/UserInfo/SetUp?format=json`.
- If `photo.contentBase64` is present, uploads the JPEG face after user setup succeeds.
- Does not log `photo.contentBase64`.
- Treats the provisioning call as successful only when the SDK transport succeeds, `rawResponse.statusCode == 1`, and `rawResponse.subStatusCode == "ok"`.
- Supports only `photo.contentType = "image/jpeg"` for face upload.

Conservative default `UserInfo` values:

- `userType`: `normal`
- `Valid.enable`: `true`
- `Valid.beginTime`: payload `access.beginTime` or `2020-01-01T00:00:00`
- `Valid.endTime`: payload `access.endTime` or `2037-12-31T23:59:59`
- `Valid.timeType`: `local`
- `doorRight`: payload `access.doorRight` or `1`
- `RightPlan`: `doorNo = 1`, `planTemplateNo = payload access.planTemplateNo or 1`

Create one user without photo:

```shell
curl -sS -X PUT 'http://localhost:16233/api/devices/1/users/456' \
  -H "Content-Type: application/json" \
  -H "X-Flow-Bridge-Token: ${FLOW_BRIDGE_TOKEN}" \
  -d '{
    "correlationId": "manual-test-456",
    "employee": {
      "personalId": 123,
      "ficha": 456,
      "employeeNo": "456",
      "name": "TEST USER",
      "identification": "8-888-888"
    },
    "access": {
      "beginTime": "2020-01-01T00:00:00",
      "endTime": "2037-12-31T23:59:59",
      "doorRight": "1",
      "planTemplateNo": "1"
    }
  }'
```

Sync one user with face:

```shell
PHOTO_BASE64="$(base64 -w 0 ./face.jpg)"

curl -sS -X PUT 'http://localhost:16233/api/devices/1/users/1001' \
  -H "Content-Type: application/json" \
  -H "X-Flow-Bridge-Token: ${FLOW_BRIDGE_TOKEN}" \
  -d "{
    \"correlationId\": \"manual-test-1001-face\",
    \"employee\": {
      \"personalId\": 123,
      \"ficha\": 1001,
      \"employeeNo\": \"1001\",
      \"name\": \"TEST USER\",
      \"identification\": \"8-888-888\"
    },
    \"access\": {
      \"beginTime\": \"2020-01-01T00:00:00\",
      \"endTime\": \"2037-12-31T23:59:59\",
      \"doorRight\": \"1\",
      \"planTemplateNo\": \"1\"
    },
    \"photo\": {
      \"name\": \"1001.jpg\",
      \"contentType\": \"image/jpeg\",
      \"contentBase64\": \"${PHOTO_BASE64}\"
    }
  }"
```

Successful user response:

```json
{
  "code": 200,
  "msg": "Success",
  "data": {
    "correlationId": "manual-test-456",
    "deviceId": "1",
    "employeeNo": "456",
    "userSynced": true,
    "photoSynced": false,
    "deleted": false,
    "bridgeStatus": "synced",
    "rawResponse": "{\"statusCode\":1,\"statusString\":\"OK\",\"subStatusCode\":\"ok\"}",
    "sdkError": null
  }
}
```

Successful user and face response:

```json
{
  "code": 200,
  "msg": "Success",
  "data": {
    "correlationId": "manual-test-1001-face",
    "deviceId": "1",
    "employeeNo": "1001",
    "userSynced": true,
    "photoSynced": true,
    "deleted": false,
    "bridgeStatus": "synced",
    "rawResponse": "{\"userRawResponse\":\"{...}\",\"faceRawResponse\":\"{...}\"}",
    "sdkError": null
  }
}
```

The device may return a compact top-level response like this, which is considered success:

```json
{
  "statusCode": 1,
  "statusString": "OK",
  "subStatusCode": "ok"
}
```

Failed user response:

```json
{
  "code": 500,
  "msg": "Provisioning failed.",
  "data": {
    "correlationId": "manual-test-456",
    "deviceId": "1",
    "employeeNo": "456",
    "userSynced": false,
    "photoSynced": false,
    "deleted": false,
    "bridgeStatus": "failed",
    "rawResponse": "",
    "sdkError": "10"
  }
}
```

### Upload Face Only

```http
PUT /api/devices/{deviceId}/users/{employeeNo}/face
```

Purpose:

- Upload or replace the JPEG face for an existing user.
- Does not create or update the user record.
- Uses `POST /ISAPI/Intelligent/FDLib/FaceDataRecord?format=json` through the cached ISUP session.

Feature and auth requirements:

- Requires `X-Flow-Bridge-Token`.
- Requires `hik.features.provisioning.enabled=true`.
- Supports only `photo.contentType = "image/jpeg"`.
- Requires non-empty valid base64 in `photo.contentBase64`.

Bridge face upload internals (phase-1 fix):

- The ISUP `NET_ECMS_ISAPIPassThrough` struct (`NET_EHOME_PTXML_PARAM`) has no
  header field, so the SDK cannot tell the device a
  `Content-Type: multipart/form-data; boundary=...` header. The bridge works
  around this by passing the boundary as a URL query parameter:
  `POST /ISAPI/Intelligent/FDLib/FaceDataRecord?format=json&boundary=<boundary>`.
  The boundary is generated ASCII-only per request.
- The request body is a standard RFC 2388
  `multipart/form-data; boundary=<boundary>` payload with two parts, in this
  fixed order:
    1. `FaceDataRecord` (JSON, `Content-Type: application/json`):
       **bare** object, NOT wrapped:
       ```json
       {"faceLibType":"blackFD","FDID":"1","FPID":"<employeeNo>"}
       ```
       Why bare: the Hikvision multipart parser extracts the part named
       `FaceDataRecord` and parses its raw bytes directly as the descriptor
       object. The earlier `{"FaceDataRecord":{"employeeNo":...,"faceLibType":"blackFace"}}`
       shape was rejected with `statusCode=5` / `subStatusCode=badJsonFormat`.
       Field semantics:
         - `faceLibType = "blackFD"`: Hikvision face-library type code for the
           master face database (the prior value `"blackFace"` is rejected by
           some firmwares).
         - `FDID = "1"`: default face library id on access-control terminals.
         - `FPID = "<employeeNo>"`: face id, mapped to the user employeeNo so
           the face binds to the provisioned access-control user.
    2. Image part (binary JPEG, `Content-Type: image/jpeg`,
       `Content-Transfer-Encoding: binary`): the normalized photo bytes. The
       field name is mode-dependent (`FaceImage` or `img`) — see
       `hik.provisioning.face-upload-mode` below.
- Different Hikvision device families expose face enrolment under different
  paths and image field names. The bridge exposes a single tunable:
  `hik.provisioning.face-upload-mode` (env `FLOW_HIK_FACE_UPLOAD_MODE`):
    - `face-data-record-faceimage` (default):
      `POST /ISAPI/Intelligent/FDLib/FaceDataRecord?format=json`, image field `FaceImage`.
    - `face-data-record-img`:
      `POST /ISAPI/Intelligent/FDLib/FaceDataRecord?format=json`, image field `img`.
    - `fd-setup-img`:
      `PUT /ISAPI/Intelligent/FDLib/FDSetUp?format=json`, image field `img`.
  If the device returns `badJsonFormat`, leave the `FaceDataRecord` JSON bare
  and switch to `face-data-record-img`; if that still fails, try `fd-setup-img`
  (which also changes the verb to `PUT`).
- Photos are normalized through `FaceImageNormalizer` before upload, mirroring
  the proven Laravel `HikvisionPhotoProcessor` configuration:
    - resize to at most `300 x 300` keeping aspect ratio;
    - re-encode as **baseline** JPEG (progressive JPEGs are rejected by some
      access-control devices, e.g. the reported `1200x1600` progressive input);
    - cap at `200 KB` by lowering JPEG quality from `0.85` down to `0.40`.
  If normalization cannot decode the input, the original bytes are uploaded
  unchanged as a fallback so the device can validate them.
- The receive timeout for the face passthrough is `10000 ms` (longer than the
  default `5000 ms` JSON timeout) because binary multipart uploads are slower.
- The bridge reads `dwReturnedXMLLen` from the SDK struct so `rawResponse`
  reflects the real device body without trailing NUL padding.

Safe debug logs emitted per face upload (metadata only, never image bytes or
`photo.contentBase64`):

- `employeeNo`
- `mode` (resolved `FaceUploadMode` name)
- original image byte length
- final (normalized) image byte length
- whether the original JPEG was progressive
- multipart total byte length
- content type (`multipart/form-data`) + boundary
- the **actual** ISAPI URL string passed to `NET_ECMS_ISAPIPassThrough`,
  including the `&boundary=` query parameter
- image field name (`FaceImage` / `img`)
- text-only multipart preamble (part ordering + field names; truncated before
  the binary image bytes)
- sdk error code + raw response length

Upload face for employee `1001`:

```shell
PHOTO_BASE64="$(base64 -w 0 ./face.jpg)"

curl -sS -X PUT 'http://localhost:16233/api/devices/1/users/1001/face' \
  -H "Content-Type: application/json" \
  -H "X-Flow-Bridge-Token: ${FLOW_BRIDGE_TOKEN}" \
  -d "{
    \"correlationId\": \"manual-face-1001\",
    \"photo\": {
      \"name\": \"1001.jpg\",
      \"contentType\": \"image/jpeg\",
      \"contentBase64\": \"${PHOTO_BASE64}\"
    }
  }"
```

Successful face response:

```json
{
  "code": 200,
  "msg": "Success",
  "data": {
    "correlationId": "manual-face-1001",
    "deviceId": "1",
    "employeeNo": "1001",
    "userSynced": false,
    "photoSynced": true,
    "deleted": false,
    "bridgeStatus": "synced",
    "rawResponse": "{\"statusCode\":1,\"statusString\":\"OK\",\"subStatusCode\":\"ok\"}",
    "sdkError": null
  }
}
```

### Verify One User

```http
GET /api/devices/{deviceId}/users/{employeeNo}/verify
```

Purpose:

- Read-only check for one user after provisioning.
- Uses the device's UserInfo search endpoint through ISUP passthrough.
- Does not change the raw `/isapi` diagnostic allowlist.

Feature and auth requirements:

- Requires `X-Flow-Bridge-Token`.
- Requires `hik.features.provisioning.enabled=true`.
- Requires `hik.features.raw-isapi.enabled=true`.

Verify employee `1001`:

```shell
curl -sS 'http://localhost:16233/api/devices/1/users/1001/verify' \
  -H "X-Flow-Bridge-Token: ${FLOW_BRIDGE_TOKEN}"
```

Successful verification response:

```json
{
  "code": 200,
  "msg": "Success",
  "data": {
    "deviceId": "1",
    "employeeNo": "1001",
    "found": true,
    "bridgeStatus": "synced",
    "rawResponse": "{\"UserInfoSearch\":{\"numOfMatches\":1,\"totalMatches\":1,\"UserInfo\":[{\"employeeNo\":\"1001\",\"name\":\"TEST USER\",\"numOfFace\":1}]}}",
    "sdkError": null
  }
}
```

After face upload, verification should show the same employee with `numOfFace = 1`. If the SDK search call succeeds but the requested `employeeNo` is not present in the response, the endpoint returns HTTP `200` with `found = false` and `bridgeStatus = "not_found"`. SDK failures, SDK errors, or empty device responses still return failed responses.

Success response:

```json
{
  "code": 200,
  "msg": "success",
  "data": {
    "correlationId": "6ec36f07-541f-4b1e-9d2b-bc1844a5c51f",
    "deviceId": "DS-K1T...",
    "employeeNo": "456",
    "userSynced": true,
    "photoSynced": true,
    "bridgeStatus": "synced"
  }
}
```

Offline response:

```json
{
  "code": 409,
  "msg": "Device is not online.",
  "data": {
    "deviceId": "DS-K1T...",
    "employeeNo": "456",
    "bridgeStatus": "offline"
  }
}
```

Validation error response:

```json
{
  "code": 422,
  "msg": "Invalid provisioning payload.",
  "data": {
    "errors": {
      "employee.employeeNo": ["employeeNo must match path parameter."]
    }
  }
}
```

### Delete One User

```http
DELETE /api/devices/{deviceId}/users/{employeeNo}
```

Purpose:

- Remove a collaborator from one online Hikvision device.

Request body:

```json
{
  "correlationId": "6ec36f07-541f-4b1e-9d2b-bc1844a5c51f",
  "tenantDb": "nomina_empresa_01",
  "laravelDeviceId": 15,
  "deviceSerial": "DS-K1T..."
}
```

Success response:

```json
{
  "code": 200,
  "msg": "success",
  "data": {
    "correlationId": "6ec36f07-541f-4b1e-9d2b-bc1844a5c51f",
    "deviceId": "DS-K1T...",
    "employeeNo": "456",
    "deleted": true,
    "bridgeStatus": "deleted"
  }
}
```

Phase-1 behavior:

- Requires `X-Flow-Bridge-Token`.
- Requires `hik.features.provisioning.enabled=true`.
- Returns `501` with `bridgeStatus = "not_implemented"` after the service layer is called.

### Raw ISAPI Diagnostic Passthrough

```http
POST /api/devices/{deviceId}/isapi
```

Purpose:

- Internal diagnostic endpoint for selected ISAPI calls while building the provisioning layer.
- This should be disabled by default or protected with a stronger internal token.
- Proves the bridge can send an ISAPI request through the cached ISUP session.

Request body:

```json
{
  "method": "GET",
  "path": "/ISAPI/System/deviceInfo",
  "body": null
}
```

Do not expose this endpoint publicly.

Phase-1 behavior:

- Requires `X-Flow-Bridge-Token`.
- Requires `hik.features.raw-isapi.enabled=true`.
- Uses `DeviceCacheService` to resolve bridge `deviceId` to the cached ISUP `loginId`.
- Does not use Laravel device IDs.
- Returns `409` when the bridge device is missing or offline.
- Allows only:
  - `GET /ISAPI/System/deviceInfo`
  - `GET /ISAPI/AccessControl/UserInfo/capabilities`
- Rejects `PUT`, `POST`, `DELETE`, user creation, face upload, and all other paths. User setup is available only through `PUT /api/devices/{deviceId}/users/{employeeNo}`.
- User verification is available only through `GET /api/devices/{deviceId}/users/{employeeNo}/verify`.

Successful response shape:

```json
{
  "code": 200,
  "msg": "Success",
  "data": {
    "deviceId": "1",
    "method": "GET",
    "path": "/ISAPI/System/deviceInfo",
    "status": "success",
    "rawResponse": "<DeviceInfo>...</DeviceInfo>",
    "sdkError": null
  }
}
```

SDK failure response shape:

```json
{
  "code": 500,
  "msg": "Raw ISAPI passthrough failed.",
  "data": {
    "deviceId": "1",
    "method": "GET",
    "path": "/ISAPI/System/deviceInfo",
    "status": "failed",
    "rawResponse": "",
    "sdkError": "10"
  }
}
```

Enable locally:

```shell
export FLOW_BRIDGE_TOKEN='change-me'
export FLOW_HIK_RAW_ISAPI_ENABLED=true
```

Get device info through ISUP:

```shell
curl -sS -X POST 'http://localhost:16233/api/devices/1/isapi' \
  -H "Content-Type: application/json" \
  -H "X-Flow-Bridge-Token: ${FLOW_BRIDGE_TOKEN}" \
  -d '{"method":"GET","path":"/ISAPI/System/deviceInfo"}'
```

Get user capability info through ISUP:

```shell
curl -sS -X POST 'http://localhost:16233/api/devices/1/isapi' \
  -H "Content-Type: application/json" \
  -H "X-Flow-Bridge-Token: ${FLOW_BRIDGE_TOKEN}" \
  -d '{"method":"GET","path":"/ISAPI/AccessControl/UserInfo/capabilities"}'
```

Unsafe requests are rejected before reaching the SDK:

```shell
curl -sS -X POST 'http://localhost:16233/api/devices/1/isapi' \
  -H "Content-Type: application/json" \
  -H "X-Flow-Bridge-Token: ${FLOW_BRIDGE_TOKEN}" \
  -d '{"method":"PUT","path":"/ISAPI/AccessControl/UserInfo/SetUp"}'
```

## Laravel Sync Payload Mapping

For one collaborator assigned to one device:

| Bridge field | Laravel source |
| --- | --- |
| `deviceId` path | `hikvision_device_info.DEVICE_SERIAL` if it matches ISUP ID; otherwise future `ISUP_DEVICE_ID` |
| `laravelDeviceId` | `hikvision_device_info.DEVICE_ID` |
| `deviceSerial` | `hikvision_device_info.DEVICE_SERIAL` |
| `employee.personalId` | `nompersonal.personal_id` |
| `employee.ficha` | `nompersonal.ficha` |
| `employee.employeeNo` | initially `nompersonal.ficha` as string |
| `employee.name` | composed full name or `nompersonal.apenom` |
| `employee.identification` | `nompersonal.cedula` |
| `photo.name` | normalized cedula or ficha plus `.jpg` |
| `photo.contentBase64` | processed JPG content |

## Security

The bridge should not be open to the public internet without authentication.

Recommended first implementation:

- Add an internal shared token header, `X-Flow-Bridge-Token`.
- Configure token through `hik.bridge.token` or `flow.bridge.token`. The current dev profile sets `hik.bridge.token` from `FLOW_BRIDGE_TOKEN`.
- Reject missing or invalid token with `401`.
- Log only metadata; never log `photo.contentBase64`.

## Retry Semantics

Laravel should interpret bridge responses as:

| HTTP status | Meaning | Laravel action |
| --- | --- | --- |
| `200` | Synced/deleted | mark `hikvision_user_info` synced/deleted |
| `400`/`422` | Invalid payload or unsupported data | mark error, do not retry automatically |
| `401`/`403` | bridge auth/config issue | retry only after configuration fix |
| `404` | unknown bridge device ID | pending or error depending on mapping confidence |
| `409` | device offline | retry with queue backoff |
| `5xx` | bridge or SDK failure | retry with queue backoff |

Recommended job settings:

- `tries = 3`
- `timeout = 120`
- `backoff = [60, 180]`

## Notes For Bridge Implementation

The Java bridge already has:

- `DeviceCacheService` for `deviceId -> loginId`.
- `HCISUPCMS.NET_ECMS_SetDeviceSessionKey` during registration.
- `ISAPIService` examples for sending ISAPI commands by login handle.

Provisioning should be implemented as a new controller/service pair rather than expanding `DeviceController`:

- `ProvisioningController`
- `HikvisionProvisioningService`
- request DTOs for user sync/delete

Keep user/face provisioning behind feature configuration until tested with real devices.
