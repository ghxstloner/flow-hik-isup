# Documentación Completa del Bridge Hikvision ISUP

## Arquitectura General

**Spring Boot 3.5.7 + WebFlux** que actúa como **puente (bridge)** entre una aplicación Laravel y dispositivos Hikvision.

### Flujo de comunicación

```
Laravel (app)
    │  REST (HTTP JSON) con X-Flow-Bridge-Token
    ▼
Bridge ISUP (este proyecto)
    │  ISUP/EHome SDK (passthrough por el túnel ISUP)
    ▼
Dispositivo Hikvision (DVR/NVR/Control de Acceso)
    │  (conectado al bridge vía ISUP/EHome 5.0)
```

### Puerto por defecto
- API HTTP: `16233/tcp`
- CMS (registro dispositivos): `7660/tcp + udp`

### Feature Flags (configurables en `hik.features.*`)

| Flag | Default | Descripción |
|---|---|---|
| `cms` | `true` | Listener de registro de dispositivos ISUP |
| `provisioning` | `false` | Sincronización de usuarios y rostros |
| `attendance-events` | `false` | Búsqueda de marcaciones (eventos ACS) |
| `raw-isapi` | `false` | Diagnóstico ISAPI raw |
| `channel-sync` | `false` | Sincronización periódica de canales |
| `stream` | `false` | Preview de video en vivo |
| `playback` | `false` | Reproducción de grabaciones |
| `voice` | `false` | Audio (TTS y upload) |
| `alarm` | `false` | Listener de alarmas |
| `storage` | `false` | Almacenamiento de fotos |

---

## 1. Detección de Dispositivos (Registro ISUP)

### ⚠️ No es un endpoint REST — es un listener pasivo

El `StartedUpRunner` inicia un **CMS listener** vía `NET_ECMS_StartListen`. Los dispositivos Hikvision se conectan **autónomamente** a este listener usando el protocolo ISUP/EHome 5.0.

Cuando un dispositivo se conecta, el callback `FRegisterCallBack` maneja estos eventos:

| dwDataType | Evento | Qué hace |
|---|---|---|
| `ENUM_DEV_ON (0)` | Dispositivo en línea | Crea/actualiza registro en `DeviceCacheService` con `deviceId`, `loginId`, `isOnline=1` |
| `ENUM_DEV_OFF (1)` | Dispositivo fuera de línea | Elimina del caché |
| `ENUM_DEV_AUTH (3)` | Autenticación EHome 5.0 | Responde con el `isupKey` de configuración |
| `ENUM_DEV_SESSIONKEY (4)` | Clave de sesión | Setea la clave de sesión vía SDK |
| `ENUM_DEV_DAS_REQ (5)` | Solicitud DAS | Responde con IP + puerto del DAS server |

### `ScheduledTask.syncHikDevice()` (cada 5 segundos)

Si `hik.features.channel-sync.enabled=true`, cada 5s sincroniza:
- Información del dispositivo (`DeviceInfo`)
- Canales (vía `GET /ISAPI/ContentMgmt/InputProxy/channels/status` para DVR/NVR, o vía control remoto XML para IPCamera)
- Estado online/offline de cada canal

### Endpoints de consulta

---

#### `GET /api/devices`

Lista todos los dispositivos registrados en el caché en memoria.

**Query params (opcionales):**

| Parámetro | Tipo | Descripción |
|---|---|---|
| `deviceId` | string | Filtra por ID (contains) |
| `isOnline` | integer | Filtra: `1` = online, `0` = offline |

**Respuesta exitosa (200):**
```json
{
  "code": 200,
  "msg": "Success",
  "data": [
    {
      "deviceId": "ABC123",
      "deviceType": "DVR",
      "isOnline": 1,
      "loginId": 42,
      "channels": [
        { "channelId": 1, "isOnline": 1 },
        { "channelId": 2, "isOnline": 0 }
      ]
    }
  ]
}
```

---

#### `GET /api/devices/{deviceId}`

Obtiene un dispositivo específico.

**Respuesta exitosa (200):**
```json
{
  "code": 200,
  "msg": "Success",
  "data": { "deviceId": "ABC123", "deviceType": "DVR", ... }
}
```

**Respuesta error (200 con code=500):**
```json
{
  "code": 500,
  "msg": "Device does not exist.",
  "data": null
}
```

---

## 2. Subir Persona + Foto (Provisioning de Usuarios)

### Autenticación

Todos los endpoints de provisioning requieren el header:

```
X-Flow-Bridge-Token: <token>
```

El token se configura vía `hik.bridge.token` o `FLOW_BRIDGE_TOKEN` env var. Si no está configurado o no coincide, responde `401 Unauthorized`.

### Feature flag

Debe estar habilitado: `hik.features.provisioning.enabled=true`

### Modelos Compartidos

#### `ProvisioningPhoto`
```json
{
  "name": "foto.jpg",
  "contentType": "image/jpeg",
  "contentBase64": "/9j/4AAQSkZJRg..."
}
```
- `contentType` debe ser `image/jpeg` (obligatorio)
- `contentBase64` debe ser base64 válido no vacío (obligatorio)
- La imagen se normaliza automáticamente: redimensiona a max 300x300, JPEG baseline (no progressive), calidad 0.85 ajustable, máximo ~200KB

#### `ProvisioningEmployee`
```json
{
  "personalId": 1,
  "ficha": 123,
  "employeeNo": "456",
  "name": "Juan Pérez",
  "identification": "12345678"
}
```
- `employeeNo` es el identificador único del usuario en el dispositivo
- `name` se usa como nombre en el dispositivo (si no se envía, usa `employeeNo`)

#### `ProvisioningAccess`
```json
{
  "userType": "normal",
  "beginTime": "2025-01-01T00:00:00",
  "endTime": "2037-12-31T23:59:59",
  "doorRight": "1",
  "planTemplateNo": "1"
}
```
- `beginTime` default: `"2020-01-01T00:00:00"`
- `endTime` default: `"2037-12-31T23:59:59"`
- `userType` default: `"normal"`
- `doorRight` default: `"1"`
- `planTemplateNo` default: `"1"`

#### `ProvisioningResponse`
```json
{
  "correlationId": "uuid",
  "deviceId": "ABC123",
  "employeeNo": "456",
  "userSynced": true,
  "photoSynced": true,
  "deleted": false,
  "bridgeStatus": "synced",
  "rawResponse": "{\"statusCode\":1,\"subStatusCode\":\"ok\"}",
  "sdkError": null
}
```

**Posibles valores de `bridgeStatus`:**

| Status | Significado |
|---|---|
| `synced` | Operación exitosa |
| `failed` | Error en el dispositivo o transporte |
| `not_found` | Usuario no encontrado (verify) |
| `offline` | Dispositivo no está online |
| `feature_disabled` | Feature flag apagado |
| `not_implemented` | Endpoint no implementado |
| `validation_error` | Error de validación en el request |
| `unauthorized` | Token inválido |

---

#### `PUT /api/devices/{deviceId}/users/{employeeNo}` — **syncUser**

Crea o actualiza un usuario en el dispositivo, opcionalmente con su foto.

**Body (UserSyncRequest):**
```json
{
  "correlationId": "uuid-opcional",
  "tenantDb": "flow_hik_tenant_1",
  "laravelDeviceId": 42,
  "deviceSerial": "DS-K1T321MFWX...",
  "employee": {
    "employeeNo": "456",
    "name": "Juan Pérez"
  },
  "access": {
    "userType": "normal",
    "beginTime": "2025-01-01T00:00:00",
    "endTime": "2037-12-31T23:59:59",
    "doorRight": "1",
    "planTemplateNo": "1"
  },
  "photo": {
    "contentType": "image/jpeg",
    "contentBase64": "/9j/4AAQ..."
  }
}
```

**Flujo interno:**

1. Valida que `employeeNo` del path coincida con `body.employee.employeeNo`
2. Valida la foto si está presente (`contentType=image/jpeg`, base64 válido)
3. Verifica que el dispositivo esté online
4. Construye JSON `{"UserInfo":{"employeeNo":"...","name":"...","userType":"normal","Valid":{"enable":true,"beginTime":"...","endTime":"...","timeType":"local"},"doorRight":"1","RightPlan":[{"doorNo":1,"planTemplateNo":"1"}]}}`
5. Envía vía ISUP pass-through a `PUT /ISAPI/AccessControl/UserInfo/SetUp?format=json`
6. Si `photo` con `contentBase64` no está vacío **y** el paso 5 fue exitoso:
   - Normaliza la imagen (redimensiona a 300x300, JPEG baseline, calidad 0.85 ajustable)
   - Sube la foto al dispositivo

**Modos de subida de foto (configurable `hik.provisioning.face-upload-mode`):**

| Modo | Método | Path ISAPI | Campo imagen |
|---|---|---|---|
| `face-data-record-faceimage` (default) | POST | `/ISAPI/Intelligent/FDLib/FaceDataRecord` | `FaceImage` |
| `face-data-record-img` | POST | `/ISAPI/Intelligent/FDLib/FaceDataRecord` | `img` |
| `fd-setup-img` | PUT | `/ISAPI/Intelligent/FDLib/FDSetUp` | `img` |
| `face-url` | POST (JSON) | `/ISAPI/Intelligent/FDLib/FaceDataRecord` | URL (no multipart) |
| `face-url-flat-faceurl-lower` | POST (JSON) | `/ISAPI/Intelligent/FDLib/FaceDataRecord` | URL (lowercase) |
| `face-url-wrapped-faceurl` | POST (JSON) | `/ISAPI/Intelligent/FDLib/FaceDataRecord` | URL (wrapped) |

**Modo URL** (face-url): El bridge guarda la imagen normalizada en `FaceUrlStore` (memoria, token único de 192 bits, TTL 5 minutos), envía un JSON con `faceURL` (o `faceUrl`) al dispositivo, y el dispositivo descarga la imagen HTTP desde el bridge.

**Respuesta exitosa (200):**
```json
{
  "code": 200,
  "msg": "Success",
  "data": {
    "correlationId": "uuid",
    "deviceId": "ABC123",
    "employeeNo": "456",
    "userSynced": true,
    "photoSynced": true,
    "deleted": false,
    "bridgeStatus": "synced",
    "rawResponse": "{\"userRawResponse\":\"...\",\"faceRawResponse\":\"...\"}",
    "sdkError": null
  }
}
```

**Respuestas de error:**

| Código HTTP | Condición |
|---|---|
| `400` | Validación: employeeNo mismatch, photo inválida |
| `401` | Token inválido o ausente |
| `409` | Dispositivo offline |
| `500` | Error en el dispositivo (`rawResponse` con detalles) |

---

#### `PUT /api/devices/{deviceId}/users/{employeeNo}/face` — **syncFace**

Solo sube/actualiza la foto del usuario, sin modificar datos del usuario.

**Body (FaceSyncRequest):**
```json
{
  "correlationId": "uuid",
  "photo": {
    "contentType": "image/jpeg",
    "contentBase64": "/9j/4AAQ..."
  }
}
```

**Respuesta:**
```json
{
  "code": 200,
  "data": {
    "correlationId": "...",
    "deviceId": "ABC123",
    "employeeNo": "456",
    "userSynced": false,
    "photoSynced": true,
    "deleted": false,
    "bridgeStatus": "synced",
    "rawResponse": "...",
    "sdkError": null
  }
}
```

---

#### `GET /api/devices/{deviceId}/users/{employeeNo}/verify` — **verifyUser**

Verifica si un usuario existe en el dispositivo.

**Flujo interno:**
1. Construye JSON `{"UserInfoSearchCond":{"searchID":"uuid","searchResultPosition":0,"maxResults":1,"EmployeeNoList":[{"employeeNo":"456"}]}}`
2. Envía vía ISUP pass-through a `POST /ISAPI/AccessControl/UserInfo/Search?format=json`

**Respuesta exitosa (200):**
```json
{
  "code": 200,
  "msg": "Success",
  "data": {
    "deviceId": "ABC123",
    "employeeNo": "456",
    "found": true,
    "bridgeStatus": "synced",
    "rawResponse": "{\"UserInfoSearch\":{\"UserInfo\":[{\"employeeNo\":\"456\",...}]}}",
    "sdkError": null
  }
}
```

Si `found: false` → `bridgeStatus: "not_found"`

---

#### `DELETE /api/devices/{deviceId}/users/{employeeNo}` — **deleteUser**

**⚠️ NO IMPLEMENTADO** — siempre retorna `bridgeStatus: "not_implemented"`.

```json
{
  "code": 501,
  "msg": "Provisioning is not implemented yet.",
  "data": {
    "deviceId": "ABC123",
    "employeeNo": "456",
    "bridgeStatus": "not_implemented"
  }
}
```

---

#### `POST /api/face-detections/import`

Importación asíncrona de datos faciales vía ISAPI.

**Params:**

| Parámetro | Tipo | Descripción |
|---|---|---|
| `deviceId` | string | ID del dispositivo |
| `xmlUrl` | string | URL pública de un archivo XML/JSON con datos de rostros |

**Flujo interno:**
1. Busca el dispositivo por `deviceId`
2. Verifica que el dispositivo tenga `loginId` (que esté logueado)
3. Construye JSON con `customFaceLibID`, `taskID`, `URL` y llama a `POST /ISAPI/Intelligent/FDLib/asyncImportDatas?format=json`

**Respuesta:**
```json
{
  "code": 200,
  "msg": "Success",
  "data": "respuesta_raw_del_dispositivo"
}
```

---

## 3. Marcaciones (Attendance Events Search)

### Feature flag: `hik.features.attendance-events.enabled=true`

#### `POST /api/devices/{deviceId}/events/search`

Busca eventos de control de acceso (marcaciones) en el dispositivo.

**Headers:**
```
X-Flow-Bridge-Token: <token>
Content-Type: application/json
```

**Body — acepta dos formatos:**

**Forma plana (recomendada):**
```json
{
  "startTime": "2026-06-17T00:00:00-05:00",
  "endTime": "2026-06-18T00:00:00-05:00",
  "maxResults": 30,
  "searchResultPosition": 0,
  "major": 0,
  "minor": 0,
  "searchID": "flow-bridge-mi-id"
}
```

**Forma legacy (wrapped en `AcsEventCond`):**
```json
{
  "AcsEventCond": {
    "startTime": "2026-06-17T00:00:00-05:00",
    "endTime": "2026-06-18T00:00:00-05:00",
    "maxResults": 30
  }
}
```

**Campos del body:**

| Campo | Tipo | Requerido | Default | Descripción |
|---|---|---|---|---|
| `startTime` | string (ISO-8601) | **Sí** | — | Inicio del rango |
| `endTime` | string (ISO-8601) | **Sí** | — | Fin del rango |
| `searchResultPosition` | integer | No | `0` | Offset para paginación |
| `maxResults` | integer | No | `30` | Tamaño de página |
| `major` | integer | No | `0` | Filtro de evento major (0=todos) |
| `minor` | integer | No | `0` | Filtro de evento minor (0=todos) |
| `searchID` | string | No | Auto-generado | ID de correlación |

**Flujo interno:**
1. Verifica token (`X-Flow-Bridge-Token`)
2. Verifica feature flag
3. Normaliza el body (unwrap `AcsEventCond` si está presente)
4. Valida `startTime` y `endTime` no vacíos
5. Verifica que el dispositivo esté online con loginId válido
6. Construye `{"AcsEventCond":{"searchID":"flow-bridge-uuid","searchResultPosition":0,"maxResults":30,"major":0,"minor":0,"startTime":"...","endTime":"..."}}`
7. Envía vía ISUP pass-through a `POST /ISAPI/AccessControl/AcsEvent?format=json` con timeout de **15 segundos**
8. Devuelve la respuesta **exacta** del dispositivo (sin parsear)

**Respuesta exitosa (200):**
```json
{
  "code": 200,
  "msg": "Success",
  "data": {
    "deviceId": "ABC123",
    "searchID": "flow-bridge-uuid",
    "status": "success",
    "eventsJson": "{\"AcsEvent\":{\"InfoList\":[{\"employeeNo\":\"456\",\"majorEventType\":5,\"minorEventType\":75,\"currentVerifyMode\":\"face\",\"deviceName\":\"ControlAcceso\",...}],\"totalMatches\":42,\"responseStatusStrg\":\"OK\"}}",
    "sdkError": null,
    "rawResponseLength": 2184
  }
}
```

**Respuestas de error:**

| Código HTTP | Condición |
|---|---|
| `400` | `startTime`/`endTime` faltantes |
| `401` | Token inválido |
| `409` | Dispositivo offline |
| `500` | Error en passthrough del dispositivo |

> **Nota:** Laravel parsea el `eventsJson` con su propio `IsapiResponseParser` — el bridge **no modifica** la respuesta del dispositivo.

---

## 4. Información del Dispositivo

#### `GET /api/devices/{deviceId}/device-info`

Obtiene metadata del dispositivo (serial, firmware, modelo). Usado por Laravel para auto-provisionar la tabla `hikvision_device_info`.

**Requiere:** `X-Flow-Bridge-Token` + `hik.features.attendance-events.enabled=true`

**Flujo interno:**
1. Envía `GET /ISAPI/System/deviceInfo` vía ISUP pass-through (timeout 10s)
2. Extrae campos del XML de respuesta usando regex simple (sin parser XML completo)

**Respuesta exitosa (200):**
```json
{
  "code": 200,
  "data": {
    "deviceId": "ABC123",
    "status": "success",
    "sdkError": null,
    "deviceName": "Control Acceso Principal",
    "deviceIdSource": "1",
    "model": "DS-K1T321MFWX",
    "serialNumber": "DS-K1T32120210101AA123456",
    "deviceLocation": "Oficina Central",
    "hikDeviceType": "doorController",
    "firmwareVersion": "V2.3.1_build20240101",
    "firmwareReleasedDate": "2024-01-01",
    "deviceTextOverlay": "ControlAcceso Ppal",
    "rawResponse": "<?xml version=\"1.0\" ...><DeviceInfo>...</DeviceInfo>"
  }
}
```

---

## 5. ISAPI Raw (Diagnóstico)

#### `POST /api/devices/{deviceId}/isapi`

Ejecuta comandos ISAPI de solo lectura en el dispositivo.

**Requiere:** `X-Flow-Bridge-Token` + `hik.features.raw-isapi.enabled=true`

**Body (RawIsapiRequest):**
```json
{
  "method": "GET",
  "path": "/ISAPI/Intelligent/FDLib/capabilities",
  "body": {}
}
```

**Paths permitidos (solo GET, solo lectura):**

| Path | Descripción |
|---|---|
| `/ISAPI/System/deviceInfo` | Info del dispositivo |
| `/ISAPI/AccessControl/UserInfo/capabilities` | Capacidades de UserInfo |
| `/ISAPI/Intelligent/FDLib` | Lista de librerías faciales |
| `/ISAPI/Intelligent/FDLib/capabilities` | Capacidades de FDLib |
| `/ISAPI/Intelligent/FDLib/FaceDataRecord/capabilities` | Capacidades de FaceDataRecord |
| `/ISAPI/Intelligent/FDLib/FDSetUp/capabilities` | Capacidades de FDSetUp |

Para paths con capacidad JSON, el bridge fuerza `?format=json`.

**Respuesta:**
```json
{
  "code": 200,
  "data": {
    "deviceId": "ABC123",
    "method": "GET",
    "path": "/ISAPI/Intelligent/FDLib/capabilities",
    "status": "success",
    "rawResponse": "{\"FDLib\":{\"faceLibType\":[\"blackFD\"],\"faceURLLen\":1024,...}}",
    "sdkError": null
  }
}
```

---

## 6. Video — Preview en Vivo

#### `POST /api/devices/{deviceId}/preview`

Inicia la transmisión de video en vivo desde un canal del dispositivo.

**Query params:**

| Parámetro | Tipo | Default | Descripción |
|---|---|---|---|
| `channelId` | integer | `1` | Número de canal |

**Flujo interno:**
1. Busca el dispositivo y el canal
2. Si el canal ya tiene un RTP activo (antiduplicado), devuelve la URL existente
3. Si no, inicia preview vía SDK y devuelve URL HTTP-FLV

**Respuesta:**
```json
{
  "code": 200,
  "msg": "Success",
  "data": {
    "httpFlv": "http://ip:puerto/live/ABC123_1.live.flv"
  }
}
```

#### `DELETE /api/devices/{deviceId}/preview` (stop, mismo channelId)

Detiene la preview.

#### `POST /api/devices/{deviceId}/playback`

Inicia reproducción de grabación.

**Query params:**

| Parámetro | Tipo | Requerido | Descripción |
|---|---|---|---|
| `channelId` | integer | No (default `1`) | Canal |
| `startTime` | string | **Sí** | Inicio (URL-encoded ISO-8601) |
| `endTime` | string | **Sí** | Fin (URL-encoded ISO-8601) |

**Respuesta:**
```json
{
  "code": 200,
  "data": {
    "httpFlv": "http://ip:puerto/playback/ABC123_1.live.flv"
  }
}
```

#### `DELETE /api/devices/{deviceId}/playback`

Detiene la reproducción.

---

## 7. Control PTZ

#### `POST /api/devices/{deviceId}/ptz`

Controla el movimiento del PTZ (Pan-Tilt-Zoom).

**Query params:**

| Parámetro | Tipo | Default | Descripción |
|---|---|---|---|
| `channelId` | integer | `1` | Canal |
| `pan` | integer | `60` | Velocidad horizontal (-100 a 100, + = derecha) |
| `tilt` | integer | `0` | Velocidad vertical (-100 a 100, + = arriba) |
| `duration` | integer | `1000` | Duración en milisegundos |

**Flujo interno:**
1. Envía `PUT /ISAPI/PTZCtrl/channels/{channelId}/continuous` con XML `<PTZData><pan>60</pan><tilt>0</tilt></PTZData>`
2. Espera `duration` ms
3. Envía stop con pan=0, tilt=0

**Respuesta:**
```json
{
  "code": 200,
  "msg": "云台控制指令已发送",
  "data": null
}
```

---

## 8. Voz (Voice Talk)

#### `POST /api/devices/{deviceId}/voice/tts`

Convierte texto a voz y lo envía al dispositivo.

**Body:**
```json
{
  "text": "Bienvenido a las oficinas centrales"
}
```

**Flujo interno:**
1. Llama a un servidor TTS externo (`hik.platform.tts-server`)
2. Convierte el MP3 resultante a μ-law PCM 8kHz mono usando FFmpeg
3. Envía el PCM al dispositivo vía `mediaStreamService.voiceTrans()`

**Respuesta:**
```json
{
  "code": 200,
  "data": "container/upload/audio/tts_uuid_8k.pcm"
}
```

#### `POST /api/devices/{deviceId}/voice/upload`

Sube un archivo de audio para enviarlo al dispositivo.

**Body:** `multipart/form-data` con campo `file`

**Flujo interno:**
1. Guarda el archivo subido
2. Convierte a μ-law PCM 8kHz mono usando FFmpeg
3. Envía al dispositivo

---

## 9. Face URL Token (Interno)

#### `GET /internal/face/{token}` 

**⚠️ Sin autenticación** — el token es la credencial.

Sirve la imagen JPEG temporal para el enrollment por URL. Usado por los modos `FACE_URL`, `FACE_URL_LOWER`, `FACE_URL_WRAPPED`.

- Token: 192 bits de `SecureRandom`, base64 URL-safe (32 caracteres)
- One-shot: se elimina al ser consumido
- TTL: 5 minutos (limpieza automática cada 5 min)
- El path prefix es configurable via `hik.face-url.path-prefix` (default `/internal/face/`)
- Response: `Content-Type: image/jpeg`, sin cache

---

## 10. Modelo de Datos: Device (Caché en Memoria)

```java
class Device {
  String deviceId;       // ID único del dispositivo
  String deviceType;     // "DVR", "NVR", "IPCamera"
  Integer isOnline;      // 0=offline, 1=online
  Integer loginId;       // Handle de login del SDK
  List<Channel> channels; // Lista de canales
}

class Channel {
  Integer channelId;     // Número de canal
  Integer isOnline;      // 0=offline, 1=online
}
```

**`DeviceCacheService`** es un `ConcurrentHashMap<String, Device>` en memoria + mapa auxiliar `loginId → deviceId`. Los datos se pierden al reiniciar el servicio.

---

## 11. Normalización de Foto (Face Image Normalizer)

**`FaceImageNormalizer`** — procesa la foto antes de enviarla al dispositivo:

1. Decodifica JPEG base64
2. Detecta si es progressive JPEG
3. Convierte a BGR (Java BufferedImage)
4. Si excede 300×300, redimensiona manteniendo aspect ratio (bilinear)
5. Recodifica como **baseline JPEG** (progressive desactivado)
6. Si el archivo excede ~200KB, reduce calidad progresivamente (0.85 → 0.40 mínimo)
7. Si falla la normalización, usa los bytes originales como fallback

---

## 12. Configuración de Face Upload

### `hik.provisioning.face-upload-mode`

| Valor | Método | Path | Campo |
|---|---|---|---|
| `face-data-record-faceimage` (default) | POST | `/ISAPI/Intelligent/FDLib/FaceDataRecord` | `FaceImage` |
| `face-data-record-img` | POST | `/ISAPI/Intelligent/FDLib/FaceDataRecord` | `img` |
| `fd-setup-img` | PUT | `/ISAPI/Intelligent/FDLib/FDSetUp` | `img` |
| `face-url` / `face-url-flat-faceurl-upper` | POST (JSON) | `/ISAPI/Intelligent/FDLib/FaceDataRecord` | URL (`faceURL`) |
| `face-url-flat-faceurl-lower` | POST (JSON) | `/ISAPI/Intelligent/FDLib/FaceDataRecord` | URL (`faceUrl`) |
| `face-url-wrapped-faceurl` / `face-url-wrapped-faceurl-upper` | POST (JSON) | `/ISAPI/Intelligent/FDLib/FaceDataRecord` | URL wrapped |

### `hik.provisioning.face-url-shape`

Controla la estructura JSON del modo URL:

| Valor | Estructura |
|---|---|
| `flat-faceurl-upper` (default) | `{"faceLibType":"blackFD","FDID":"1","FPID":"<e>","faceURL":"http://..."}` |
| `flat-faceurl-lower` | `{"faceLibType":"blackFD","FDID":"1","FPID":"<e>","faceUrl":"http://..."}` |
| `wrapped-faceurl-upper` | `{"FaceDataRecord":{"faceLibType":"blackFD","FDID":"1","FPID":"<e>","faceURL":"http://..."}}` |
| `wrapped-faceurl-lower` | `{"FaceDataRecord":{"faceLibType":"blackFD","FDID":"1","FPID":"<e>","faceUrl":"http://..."}}` |

### `hik.provisioning.face-lib-type`

Valor de `faceLibType` en URL mode (default: `"blackFD"`).

### `hik.provisioning.fdid`

ID de la librería facial (default: `"1"`).

---

## 13. Códigos de Estado HTTP Usados

| Código | Uso |
|---|---|
| `200` | Éxito |
| `400` | Error de validación |
| `401` | No autorizado (token) |
| `409` | Dispositivo offline |
| `500` | Error interno / error del dispositivo |
| `501` | No implementado (deleteUser) |
| `503` | Feature deshabilitado |

---

## 14. Resumen de Endpoints (Tabla Rápida)

| Método | Ruta | Feature Flag | Auth | Timeout | Descripción |
|---|---|---|---|---|---|
| `GET` | `/api/devices` | — | No | — | Listar dispositivos registrados |
| `GET` | `/api/devices/{deviceId}` | — | No | — | Obtener un dispositivo |
| `PUT` | `/api/devices/{deviceId}/users/{employeeNo}` | `provisioning` | Sí | SDK | Crear/actualizar usuario + foto |
| `PUT` | `/api/devices/{deviceId}/users/{employeeNo}/face` | `provisioning` | Sí | 10s | Solo subir foto |
| `GET` | `/api/devices/{deviceId}/users/{employeeNo}/verify` | `provisioning` + `rawIsapi` | Sí | SDK | Verificar si usuario existe |
| `DELETE` | `/api/devices/{deviceId}/users/{employeeNo}` | `provisioning` | Sí | — | ❌ NO IMPLEMENTADO |
| `POST` | `/api/devices/{deviceId}/events/search` | `attendance-events` | Sí | 15s | Buscar marcaciones |
| `GET` | `/api/devices/{deviceId}/device-info` | `attendance-events` | Sí | 10s | Info del dispositivo |
| `POST` | `/api/devices/{deviceId}/isapi` | `raw-isapi` | Sí | SDK | ISAPI diagnóstico (solo GET) |
| `POST` | `/api/face-detections/import` | — | No | SDK | Importación asíncrona de rostros |
| `POST` | `/api/devices/{deviceId}/preview` | `stream` | No | — | Iniciar preview |
| `DELETE` | `/api/devices/{deviceId}/preview` | `stream` | No | — | Detener preview |
| `POST` | `/api/devices/{deviceId}/playback` | `playback` | No | — | Iniciar playback |
| `DELETE` | `/api/devices/{deviceId}/playback` | `playback` | No | — | Detener playback |
| `POST` | `/api/devices/{deviceId}/ptz` | `channel-sync` | No | — | Control PTZ |
| `POST` | `/api/devices/{deviceId}/voice/tts` | `voice` | No | — | TTS voz |
| `POST` | `/api/devices/{deviceId}/voice/upload` | `voice` | No | — | Upload audio |
| `GET` | `/internal/face/{token}` | — | Token interno | — | Servir JPEG temporal |
