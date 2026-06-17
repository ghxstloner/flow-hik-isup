# Hikvision ISUP ACS

Spring Boot service for Hikvision ISUP ACS device registration.

The current scope is intentionally small:

- start the Spring Boot application
- initialize the ISUP CMS SDK
- listen for device registration callbacks
- handle ISUP authentication, session key, DAS request, online, and offline callbacks
- keep online devices in an in-memory registry
- expose `GET /api/devices`
- support Linux SDK loading and Docker packaging

The following features are disabled by default and should stay off until they are needed again:

- video preview and streaming
- playback
- voice talk
- ZLM media server
- picture storage server
- TTS
- camera, NVR, and DVR channel sync
- scheduled channel polling

## Configuration

Use the `dev` profile and configure the service through environment variables.

Required for a normal ACS registration setup:

```shell
export HIK_PUBLIC_IP=your-server-ip-or-dns
export HIK_CMS_LISTEN_IP=0.0.0.0
export HIK_CMS_LISTEN_PORT=7660
export HIK_DAS_IP="$HIK_PUBLIC_IP"
export HIK_DAS_PORT=7660
export HIK_ISUP_KEY=hik12345
```

Feature flags:

```yaml
hik:
  features:
    cms:
      enabled: true
    alarm:
      enabled: false
    stream:
      enabled: false
    storage:
      enabled: false
    voice:
      enabled: false
    playback:
      enabled: false
    channel-sync:
      enabled: false
    provisioning:
      enabled: false
    raw-isapi:
      enabled: false
  bridge:
    token: ${FLOW_BRIDGE_TOKEN:}
```

The corresponding environment variables are:

```shell
HIK_CMS_ENABLED=true
HIK_ALARM_ENABLED=false
HIK_STREAM_ENABLED=false
HIK_STORAGE_ENABLED=false
HIK_VOICE_ENABLED=false
HIK_PLAYBACK_ENABLED=false
HIK_CHANNEL_SYNC_ENABLED=false
FLOW_HIK_PROVISIONING_ENABLED=false
FLOW_HIK_RAW_ISAPI_ENABLED=false
FLOW_BRIDGE_TOKEN=change-me
```

## Ports

For the registration-only flow, allow:

- `7660/tcp`
- `7660/udp` if your device requires UDP on the same ACS port
- `16233/tcp` for the HTTP API

## API

List currently registered devices:

```shell
curl http://localhost:16233/api/devices
```

Filter by device ID or online state:

```shell
curl "http://localhost:16233/api/devices?deviceId=DEVICE_ID&isOnline=1"
```

Internal provisioning endpoints are disabled by default and require `X-Flow-Bridge-Token` when enabled:

```shell
curl -X PUT "http://localhost:16233/api/devices/1/users/456" \
  -H "Content-Type: application/json" \
  -H "X-Flow-Bridge-Token: $FLOW_BRIDGE_TOKEN" \
  -d '{"employee":{"employeeNo":"456"}}'
```

## Docker

Build the image:

```shell
docker build -t hik-isup:latest .
```

Run with host networking so the native SDK can bind the configured listener ports:

```shell
docker run -d \
  --network=host \
  --restart=always \
  --name hik-isup \
  -e HIK_PUBLIC_IP=your-server-ip-or-dns \
  -e HIK_CMS_LISTEN_IP=0.0.0.0 \
  -e HIK_DAS_IP=your-server-ip-or-dns \
  -e HIK_ISUP_KEY=hik12345 \
  hik-isup:latest
```

View logs:

```shell
docker logs -f --tail=300 hik-isup
```
