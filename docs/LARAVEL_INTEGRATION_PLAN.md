# Laravel Integration Plan

## Scope

This bridge should stay responsible for Hikvision ISUP connectivity and device-session operations. The Laravel backend remains the source of truth for tenants, collaborators, device assignments, photos, attendance marks, queues, and audit logs.

No Laravel migrations or provisioning implementation are required for this step.

## Bridge Verification

Current bridge files inspected:

- `pom.xml`
- `Dockerfile`
- `src/main/resources/application-dev.yml`
- `src/main/java/com/oldwei/isup/runner/StartedUpRunner.java`
- `src/main/java/com/oldwei/isup/sdk/service/impl/FRegisterCallBack.java`
- `src/main/java/com/oldwei/isup/service/DeviceCacheService.java`
- `src/main/java/com/oldwei/isup/controller/DeviceController.java`
- `src/main/java/com/oldwei/isup/config/HikFeatureProperties.java`

Findings:

- `pom.xml` targets Java 17 and includes the local Hikvision SDK jars with `system` scope. Spring Boot repackaging includes system-scoped dependencies.
- `Dockerfile` uses OpenJDK 21 to run the Java 17-targeted artifact, which is compatible for runtime.
- `application-dev.yml` keeps the public/listen ISUP address configuration under environment variables.
- `HikFeatureProperties` defaults to CMS enabled and alarm, stream, storage, voice, playback, and channel-sync disabled.
- The registration-only path is preserved:
  - `StartedUpRunner` starts the CMS listener when `hik.features.cms.enabled` is true.
  - `FRegisterCallBack` handles ISUP auth with `hik.isup.isupKey`.
  - `FRegisterCallBack` stores the device session key via `NET_ECMS_SetDeviceSessionKey`.
  - `FRegisterCallBack` answers DAS requests using `hik.isup.dasServer`.
  - `FRegisterCallBack` handles device online by caching `deviceId`, `loginId`, and `isOnline = 1`.
  - `DeviceCacheService` holds registered devices in memory.
  - `GET /api/devices` and `GET /api/devices/{deviceId}` expose the cache.

Compile verification:

- `mvn -q -DskipTests compile` could not be run because `mvn` is not available on PATH in this shell.
- Static inspection did not show a registration-flow compile issue, but actual compile should be run on a machine/container with Maven installed.

## Laravel Patterns Found

Laravel backend inspected at `C:\wamp64\www\aitsa_rrhh\flow`.

Relevant ZKTeco files:

- `app/Application/ZKTeco/EmployeeDeviceSync/EmployeeDeviceSyncService.php`
- `app/Infrastructure/ZKTeco/Persistence/TenantEmployeeDeviceSyncRepository.php`
- `app/Infrastructure/ZKTeco/Commands/ZKTecoDeviceCommandWriter.php`
- `app/Infrastructure/ZKTeco/Photos/TenantEmployeeDevicePhotoProcessor.php`
- `app/Application/Employee/Events/EmployeeDeviceSyncRequested.php`
- `app/Application/Employee/Listeners/SyncDeviceCommandListener.php`
- `app/Providers/ZKTecoServiceProvider.php`
- `routes/domains/zkteco-device.php`

Current ZKTeco sync model:

- Collaborator-device assignments are read from `personal_dispositivos`.
- Employee identity and photos are read from `nompersonal`.
- Devices are stored in `profacex_device_info`.
- Prepared user state is stored in `profacex_user_info`.
- Commands are queued in ZKTeco command tables through `DeviceCommandWriterInterface`.
- `EmployeeDeviceSyncRequested` is queued and handled by `SyncDeviceCommandListener`.
- Device-wide sync is triggered by `POST /api/zkteco/device/devices/sync`.

Relevant attendance files:

- `app/Services/AmaxoniaMarcacionService.php`
- `app/Services/ZKTeco/ProFaceX/GlobalAttLogProcessor.php`
- `app/Application/Attendance/Processing/ProcessAttendanceLogs.php`
- `app/Services/Hikvision/HikvisionAttendanceService.php`

Attendance pattern:

- Device event logs are converted into `reloj_marcaciones`.
- Duplicate checks include `ficha_empleado`, `fecha`, `hora`, and `dispositivo`.
- The app also checks `nomempresa.minutos_intervalo_marcaciones` to suppress near-duplicate marks.
- Attendance recalculation is triggered per affected `ficha` and date, either through `ProcessAttendanceJob` or `ProcessClockMarksForDay`.

Current Hikvision files:

- `app/Services/Hikvision/HikvisionUserSyncService.php`
- `app/Services/Hikvision/HikvisionPhotoProcessor.php`
- `app/Services/Hikvision/HikvisionAttendanceService.php`
- `app/Jobs/Hikvision/HikvisionSyncUserJob.php`
- `app/Http/Controllers/Hikvision/HikvisionDeviceController.php`
- `app/Http/Controllers/Hikvision/HikvisionSyncController.php`
- `app/Models/Hikvision/HikvisionDeviceInfo.php`
- `app/Models/Hikvision/HikvisionUserInfo.php`
- `routes/domains/hikvision.php`
- `database/migrations/2026_05_14_000003_create_hikvision_tables_on_tenant_databases.php`

Current Hikvision model:

- `hikvision_device_info` stores direct-IP ISAPI device credentials and status.
- `hikvision_user_info` stores per-device user sync status.
- `hikvision_event_log` stores polled access events.
- `HikvisionUserSyncService` currently talks directly to device IP/port with digest auth.
- `HikvisionSyncUserJob` retries direct device provisioning failures.
- `HikvisionAttendanceService` polls direct ISAPI events, writes `reloj_marcaciones`, and dispatches attendance processing.

## Recommended Integration

Add a Laravel-side service such as:

- `App\Services\Hikvision\Isup\FlowHikIsupClient`
- `App\Services\Hikvision\Isup\HikvisionIsupUserSyncService`

Responsibilities:

- `FlowHikIsupClient`: low-level HTTP client for this bridge.
- `HikvisionIsupUserSyncService`: Laravel orchestration equivalent to `HikvisionUserSyncService`, but for ISUP-online devices.

The existing `HikvisionSyncUserJob` should either:

- branch by device transport, for example `TRANSPORT = direct_isapi | isup`, or
- be paired with a new `HikvisionIsupSyncUserJob`.

Prefer a new job first if the current direct-IP flow is in use, because it limits regression risk.

## Device Mapping

Laravel has two identifiers that matter:

- `hikvision_device_info.DEVICE_ID`: Laravel primary key.
- `hikvision_device_info.DEVICE_SERIAL`: stable Hikvision serial/device identifier.

The bridge currently exposes ISUP `deviceId` from the registration callback. For the first ISUP integration, map:

- Laravel `hikvision_device_info.DEVICE_SERIAL` to bridge `deviceId`.

Current field observation:

- The first tested ISUP device is exposed by the bridge as `deviceId = "1"` with `isOnline = 1` and `loginId = 0`.
- This may not match Laravel `hikvision_device_info.DEVICE_SERIAL`.
- If the real Laravel serial differs from the ISUP registration `deviceId`, Laravel needs a separate nullable mapping field such as `ISUP_DEVICE_ID`.

Required field check before implementation:

- If ISUP `deviceId` differs from the existing serial stored in Laravel, add a nullable field such as `ISUP_DEVICE_ID` or `ISUP_DEVICE_CODE` to `hikvision_device_info`.
- If they are the same in real devices, no migration is needed for mapping.

Do not overload `DEVICE_ID`; it is Laravel's local numeric primary key.

## Sync One Collaborator To One Device

Laravel should build the payload from:

- `nompersonal.personal_id`
- `nompersonal.ficha`
- `nompersonal.cedula`
- `nompersonal.nombres`, `nombres2`, `apellidos`, `apellido_materno`, or fallback `apenom`
- `nompersonal.foto`
- `personal_dispositivos.personal_id`
- `personal_dispositivos.device_id`
- `hikvision_device_info.DEVICE_SERIAL`

Recommended employee number:

- Use `ficha` as `employeeNo`, matching existing Hikvision direct-IP behavior.
- If global-device routing is later needed, copy the ZKTeco `PinRoutingUtil` pattern rather than changing the bridge contract.

Photo handling:

- Laravel should continue to resolve and process photos.
- For the bridge API, send a public/internal `photoUrl` if the bridge can fetch Laravel storage, or send `photoContentBase64` for a closed-network first version.
- Directly sending base64 is easier to queue and retry because the job payload is self-contained, but it is larger.

## Offline Queue And Retry

Use Laravel queue semantics first:

- Before dispatching provisioning, call bridge `GET /api/devices/{deviceId}`.
- If not found or `isOnline != 1`, mark `hikvision_user_info.SYNC_STATUS = pending` and release/retry the job.
- If the bridge returns an explicit offline response from provisioning, keep the Laravel status as `pending` or `error` depending on retry exhaustion.
- Use existing job retry/backoff style from `HikvisionSyncUserJob`: 3 attempts, timeout around 120 seconds, increasing backoff.

Recommended first behavior:

- Offline device: job releases with backoff and does not mark final error until attempts are exhausted.
- Validation error such as missing photo or invalid user: mark `SYNC_STATUS = error`.
- Success: mark `SYNC_STATUS = synced`, `PHOTO_SYNCED = true/false`, `LAST_SYNCED_AT = now()`.

Later improvement:

- Bridge can emit an online callback to Laravel when a device registers. Laravel can then dispatch pending `hikvision_user_info` rows for that `DEVICE_SERIAL`/`ISUP_DEVICE_ID`.

## Bridge Phase 1

The bridge now exposes disabled-by-default provisioning skeleton endpoints:

- `PUT /api/devices/{deviceId}/users/{employeeNo}`
- `DELETE /api/devices/{deviceId}/users/{employeeNo}`
- `POST /api/devices/{deviceId}/isapi`

All require `X-Flow-Bridge-Token`. The token can be configured as `hik.bridge.token` or `flow.bridge.token`; `application-dev.yml` reads `FLOW_BRIDGE_TOKEN`.

Current behavior is intentionally conservative:

- `hik.features.provisioning.enabled=false` by default.
- `hik.features.raw-isapi.enabled=false` by default.
- When enabled, user sync/delete calls reach the service layer and return `501` with `bridgeStatus = "not_implemented"`.
- Raw ISAPI passthrough is implemented only for safe diagnostics:
  - `GET /ISAPI/System/deviceInfo`
  - `GET /ISAPI/AccessControl/UserInfo/capabilities`
- Raw ISAPI returns `status = "success"` with `rawResponse` when the SDK call succeeds, or `status = "failed"` with `sdkError` when the SDK call fails.
- Missing/offline provisioning devices return `409` with `bridgeStatus = "offline"`.
- Missing/offline raw ISAPI devices return `409` with `status = "failed"` and `sdkError = "offline"`.
- The service logs metadata only and does not log photo base64.

## Implementation Sequence

1. Confirm real device mapping: compare bridge `GET /api/devices` `deviceId` with Laravel `hikvision_device_info.DEVICE_SERIAL`.
2. Add bridge provisioning endpoints documented in `docs/HIKVISION_PROVISIONING_API.md`.
3. Add `FlowHikIsupClient` in Laravel with config keys for base URL, timeout, and shared token.
4. Add a queued Laravel job for ISUP provisioning.
5. Add a transport selector to Hikvision devices only if direct-IP and ISUP devices must coexist.
6. Add focused tests for payload building, offline handling, and sync status transitions.
7. Only then consider migrations for `ISUP_DEVICE_ID`, `TRANSPORT`, or sync-attempt metadata if the real mapping/status cannot be represented by existing fields.
