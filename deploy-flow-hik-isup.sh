#!/usr/bin/env bash
set -Eeuo pipefail

APP_DIR="${APP_DIR:-/var/www/html/flow-hik-isup}"
IMAGE_NAME="${IMAGE_NAME:-flow-hik-isup:dev}"
CONTAINER_NAME="${CONTAINER_NAME:-flow-hik-isup}"
ENV_FILE="${ENV_FILE:-/tmp/flow-hik-isup.env}"

FLOW_BRIDGE_TOKEN="${FLOW_BRIDGE_TOKEN:-change-me-123}"
FLOW_PUBLIC_IP="${FLOW_PUBLIC_IP:-104.248.187.45}"
FLOW_HTTP_PORT="${FLOW_HTTP_PORT:-16233}"

echo "============================================================"
echo " Flow Hik ISUP deploy"
echo " APP_DIR:        ${APP_DIR}"
echo " IMAGE_NAME:     ${IMAGE_NAME}"
echo " CONTAINER_NAME: ${CONTAINER_NAME}"
echo " ENV_FILE:       ${ENV_FILE}"
echo "============================================================"

if [ "$(id -u)" -ne 0 ]; then
  echo "ERROR: ejecuta este script como root o con sudo."
  exit 1
fi

if [ ! -d "${APP_DIR}" ]; then
  echo "ERROR: no existe APP_DIR: ${APP_DIR}"
  exit 1
fi

command -v git >/dev/null 2>&1 || { echo "ERROR: git no esta instalado."; exit 1; }
command -v mvn >/dev/null 2>&1 || { echo "ERROR: mvn no esta en PATH."; exit 1; }
command -v docker >/dev/null 2>&1 || { echo "ERROR: docker no esta instalado."; exit 1; }
command -v jar >/dev/null 2>&1 || { echo "ERROR: jar no esta en PATH."; exit 1; }

cd "${APP_DIR}"

echo
echo "==> 1/8 Git pull"
git pull

echo
echo "==> 2/8 Maven build"
mvn clean package -DskipTests

JAR_PATH="${APP_DIR}/target/hik-isup-0.0.1.jar"
if [ ! -f "${JAR_PATH}" ]; then
  echo "ERROR: Maven termino pero no existe ${JAR_PATH}"
  exit 1
fi

echo
echo "==> 3/8 Verificando clases esperadas dentro del JAR"
REQUIRED_CLASSES=(
  "com/oldwei/isup/service/FaceUrlShape.class"
  "com/oldwei/isup/controller/AttendanceController.class"
  "com/oldwei/isup/service/AttendanceEventsSearchService.class"
  "com/oldwei/isup/controller/DeviceInfoController.class"
  "com/oldwei/isup/service/DeviceInfoService.class"
)

for klass in "${REQUIRED_CLASSES[@]}"; do
  if jar tf "${JAR_PATH}" | grep -q "${klass}"; then
    echo "OK: ${klass}"
  else
    echo "ERROR: falta clase en JAR: ${klass}"
    echo "Esto indica que el JAR no contiene los cambios esperados."
    exit 1
  fi
done

echo
echo "==> 4/8 Escribiendo env-file del contenedor"
cat > "${ENV_FILE}" <<EOF
FLOW_BRIDGE_TOKEN=${FLOW_BRIDGE_TOKEN}
FLOW_HIK_PROVISIONING_ENABLED=true
FLOW_HIK_RAW_ISAPI_ENABLED=true
FLOW_HIK_ATTENDANCE_EVENTS_ENABLED=true

FLOW_HIK_FACE_UPLOAD_MODE=face-url
FLOW_HIK_FACE_URL_BASE=http://${FLOW_PUBLIC_IP}:${FLOW_HTTP_PORT}
FLOW_HIK_FACE_URL_SHAPE=flat-faceurl-upper
FLOW_HIK_FACE_LIB_TYPE=blackFD
FLOW_HIK_FACE_FDID=1
EOF

chmod 600 "${ENV_FILE}"
echo "OK: ${ENV_FILE}"

echo
echo "==> 5/8 Docker build sin cache"
docker build --no-cache -t "${IMAGE_NAME}" .

echo
echo "==> 6/8 Recreando contenedor"
docker rm -f "${CONTAINER_NAME}" 2>/dev/null || true

docker run -d \
  --name "${CONTAINER_NAME}" \
  --network=host \
  --restart=unless-stopped \
  --env-file "${ENV_FILE}" \
  "${IMAGE_NAME}"

echo
echo "==> 7/8 Esperando arranque"
sleep 8

echo
echo "==> Logs recientes"
docker logs --tail=120 "${CONTAINER_NAME}" || true

echo
echo "==> 8/8 Smoke tests"

echo
echo "-- /api/devices"
curl -sS "http://127.0.0.1:${FLOW_HTTP_PORT}/api/devices" || {
  echo
  echo "ERROR: no responde /api/devices"
  exit 1
}
echo

echo
echo "-- /api/devices/1/device-info"
curl -sS "http://127.0.0.1:${FLOW_HTTP_PORT}/api/devices/1/device-info" \
  -H "X-Flow-Bridge-Token: ${FLOW_BRIDGE_TOKEN}" || true
echo

echo
echo "-- Verificando clases dentro del contenedor"
docker exec "${CONTAINER_NAME}" sh -lc \
  'jar tf /opt/hik-isup/hik-isup-0.0.1.jar | grep -E "FaceUrlShape|AttendanceController|AttendanceEventsSearchService|DeviceInfoController|DeviceInfoService"'

echo
echo "============================================================"
echo " Deploy completado."
echo
echo " Prueba manual recomendada:"
cat <<'EOF'
curl -sS -X POST 'http://127.0.0.1:16233/api/devices/1/events/search' \
  -H 'Content-Type: application/json' \
  -H 'X-Flow-Bridge-Token: change-me-123' \
  -d '{
    "searchID": "deploy-smoke-events",
    "searchResultPosition": 0,
    "maxResults": 10,
    "major": 0,
    "minor": 0,
    "startTime": "2026-06-17T18:55:00-05:00",
    "endTime": "2026-06-17T19:05:00-05:00"
  }'
EOF
echo "============================================================"
