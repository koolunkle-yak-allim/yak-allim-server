#!/usr/bin/env bash
set -e

# 이전 빌드로 블루-그린 색상을 전환하는 롤백 스크립트.
# 사용법: ./rollback.sh <IMAGE_TAG>
#   예) ./rollback.sh 42        (yak-allim-backend:42 로 롤백)
# IMAGE_TAG는 scripts/deploy.sh가 이미지 정리 시 보존한(IMAGE_RETENTION_COUNT개) 태그여야 합니다.

echo "=== [Rollback Stage] Blue-Green Rollback Started ==="

ROLLBACK_TAG="${1:-${ROLLBACK_TAG:-}}"
if [ -z "${ROLLBACK_TAG}" ]; then
    echo "오류: 롤백할 이미지 태그(빌드 번호)를 인자로 전달하세요. 예) ./rollback.sh 42" >&2
    exit 1
fi

DEPLOY_DIR="${DEPLOY_DIR:-${WORKSPACE}/deploy}"
IMAGE_REPOSITORY="${IMAGE_REPOSITORY:-yak-allim-backend}"
IMAGE_NAME="${IMAGE_REPOSITORY}:${ROLLBACK_TAG}"
N8N_WEBHOOK_URL="${N8N_WEBHOOK_URL:-http://yak-allim-n8n:5678/webhook/ocr}"
OLD_CONTAINER_DRAIN_WAIT_SECONDS="${OLD_CONTAINER_DRAIN_WAIT_SECONDS:-30}"
OLD_CONTAINER_STOP_TIMEOUT_SECONDS="${OLD_CONTAINER_STOP_TIMEOUT_SECONDS:-70}"

if ! docker image inspect "${IMAGE_NAME}" >/dev/null 2>&1; then
    echo "오류: 로컬에 ${IMAGE_NAME} 이미지가 없습니다. (deploy.sh의 이미지 정리 주기에 의해 이미 삭제되었을 수 있습니다)" >&2
    exit 1
fi

echo "=== 롤백 대상 이미지: ${IMAGE_NAME} ==="

# 1. 블루-그린 포트 및 컨테이너 이름 결정 (8082 <-> 8083)
IS_BLUE_ACTIVE=$(docker ps --filter "name=^/yak-allim-backend-blue$" --filter "status=running" -q 2>/dev/null || true)

if [ -n "$IS_BLUE_ACTIVE" ]; then
    TARGET_PORT=8083
    TARGET_NAME="yak-allim-backend-green"
    OLD_CONTAINER_NAME="yak-allim-backend-blue"
else
    TARGET_PORT=8082
    TARGET_NAME="yak-allim-backend-blue"
    OLD_CONTAINER_NAME="yak-allim-backend-green"
fi

echo "=== Target 배포 설정 ==="
echo "Target Port: ${TARGET_PORT}"
echo "Target Container Name: ${TARGET_NAME}"

MY_CONTAINER_ID=$(hostname 2>/dev/null || true)

docker stop "${TARGET_NAME}" 2>/dev/null || true
docker rm -f "${TARGET_NAME}" 2>/dev/null || true

# 2. 롤백 대상 이미지로 신규(구버전) 컨테이너 생성 — 모델/키는 배포 디렉터리에 남아있는 것을 재사용
docker create \
    --name "${TARGET_NAME}" \
    --restart unless-stopped \
    --network app-network \
    -p "${TARGET_PORT}:8081" \
    -e OCR_N8N_WEBHOOK_SECRET="${OCR_N8N_WEBHOOK_SECRET}" \
    "${IMAGE_NAME}" \
    --server.port=8081 \
    --notification.firebase.key-path="file:/app/yak-allim-firebase-key.json" \
    --ocr.engine.onnx.detection-model-path="file:/app/models/ch_PP-OCRv4_det_infer.onnx" \
    --ocr.engine.onnx.recognition-model-path="file:/app/models/korean_PP-OCRv4_rec_infer.onnx" \
    --ocr.engine.onnx.recognition-dictionary-path="file:/app/models/korean_dict.txt" \
    --ocr.n8n.webhook-url="${N8N_WEBHOOK_URL}"

if [ -f "${DEPLOY_DIR}/yak-allim-firebase-key.json" ]; then
    docker cp "${DEPLOY_DIR}/yak-allim-firebase-key.json" "${TARGET_NAME}:/app/yak-allim-firebase-key.json"
fi

if [ -d "${DEPLOY_DIR}/models" ]; then
    docker cp "${DEPLOY_DIR}/models" "${TARGET_NAME}:/app/"
fi

docker start "${TARGET_NAME}"

# 3. 헬스 체크
HEALTH_SUCCESS=false
echo "=== 롤백 컨테이너(${TARGET_NAME}) Actuator HTTP 헬스 체크 진행 중... ==="

TARGET_IP=$(docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' "${TARGET_NAME}" 2>/dev/null || true)

for retry in $(seq 1 40); do
    sleep 4
    HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" "http://${TARGET_NAME}:8081/actuator/health" 2>/dev/null || true)
    if [ "$HTTP_CODE" != "200" ] && [ -n "$TARGET_IP" ]; then
        HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" "http://${TARGET_IP}:8081/actuator/health" 2>/dev/null || true)
    fi
    if [ "$HTTP_CODE" != "200" ]; then
        HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" "http://localhost:${TARGET_PORT}/actuator/health" 2>/dev/null || true)
    fi

    if [ "$HTTP_CODE" = "200" ]; then
        HEALTH_SUCCESS=true
        break
    fi
    echo "롤백 컨테이너 구동 확인 중... (HTTP Status: ${HTTP_CODE:-000}, 시도 $retry/40)"
done

if [ "$HEALTH_SUCCESS" != "true" ]; then
    echo "=== 롤백 컨테이너(${TARGET_NAME}) 헬스 체크 실패 — 롤백 중단 ==="
    docker logs --tail 30 "${TARGET_NAME}" 2>/dev/null || true
    docker rm -f "${TARGET_NAME}" 2>/dev/null || true
    exit 1
fi

echo "=== 롤백 컨테이너(${TARGET_NAME}) 정상 구동 완료 (PORT: ${TARGET_PORT}) ==="
docker logs --tail 25 "${TARGET_NAME}"

# 4. Nginx 포트 스위칭
echo "=== Nginx 포트 스위칭 (Target: ${TARGET_NAME} / Port: ${TARGET_PORT}) 진행 ==="
mkdir -p "${DEPLOY_DIR}"
printf 'set \x24service_url http://%s:8081;\n' "${TARGET_NAME}" > "${DEPLOY_DIR}/service-url.inc"
docker exec yak-allim-nginx sh -c "printf 'set \x24service_url http://%s:8081;\n' ${TARGET_NAME} > /etc/nginx/conf.d/service-url.inc"
docker exec yak-allim-nginx nginx -s reload

# 5. 이전(방금 롤백으로 대체된) 컨테이너 드레이닝 후 정지 및 삭제
OLD_CONTAINER_ID=$(docker ps --filter "name=^/${OLD_CONTAINER_NAME}$" --filter "status=running" -q 2>/dev/null || true)
if [ -n "$OLD_CONTAINER_ID" ]; then
    if [ -n "$MY_CONTAINER_ID" ] && [ "$OLD_CONTAINER_ID" = "$MY_CONTAINER_ID" ]; then
        echo "Warning: Jenkins 컨테이너는 정지 대상에서 제외합니다."
    else
        echo "=== 이전 컨테이너(${OLD_CONTAINER_NAME}) 드레이닝 대기 (${OLD_CONTAINER_DRAIN_WAIT_SECONDS}초)... ==="
        sleep "${OLD_CONTAINER_DRAIN_WAIT_SECONDS}"
        echo "=== 이전 컨테이너(${OLD_CONTAINER_NAME}) graceful stop (타임아웃 ${OLD_CONTAINER_STOP_TIMEOUT_SECONDS}초) 및 정리 중... ==="
        docker stop -t "${OLD_CONTAINER_STOP_TIMEOUT_SECONDS}" "$OLD_CONTAINER_ID" 2>/dev/null || true
        docker rm -f "$OLD_CONTAINER_ID" 2>/dev/null || true
    fi
fi

echo "=== 롤백 완료: ${IMAGE_NAME} ==="
