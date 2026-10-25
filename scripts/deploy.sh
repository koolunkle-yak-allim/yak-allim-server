#!/usr/bin/env bash
set -e

echo "=== [Deploy Stage] Blue-Green Deployment Started ==="

DEPLOY_DIR="${DEPLOY_DIR:-${WORKSPACE}/deploy}"
IMAGE_REPOSITORY="${IMAGE_REPOSITORY:-yak-allim-backend}"
IMAGE_NAME="${IMAGE_NAME:-${IMAGE_REPOSITORY}:latest}"
IMAGE_RETENTION_COUNT="${IMAGE_RETENTION_COUNT:-5}"
N8N_WEBHOOK_URL="${N8N_WEBHOOK_URL:-http://yak-allim-n8n:5678/webhook/ocr}"
# 작업 상태 저장소(yak-allim-infra의 Redis). 블루·그린 컨테이너가 함께 쓴다.
REDIS_CONTAINER_NAME="${REDIS_CONTAINER_NAME:-yak-allim-redis}"
# 전환 직후 곧바로 종료하지 않고, 처리 중이던 요청이 끝날 시간을 준 뒤 graceful stop 함.
OLD_CONTAINER_DRAIN_WAIT_SECONDS="${OLD_CONTAINER_DRAIN_WAIT_SECONDS:-30}"
OLD_CONTAINER_STOP_TIMEOUT_SECONDS="${OLD_CONTAINER_STOP_TIMEOUT_SECONDS:-70}"
# Dockerfile의 app 사용자 UID
APP_UID="${APP_UID:-10001}"

# 0. 작업 상태 저장소 확인. Redis 없이 띄우면 헬스체크가 실패하므로, 빌드 전에 원인을 알려 주고 멈춘다.
if [ -z "$(docker ps --filter "name=^/${REDIS_CONTAINER_NAME}$" --filter "status=running" -q 2>/dev/null || true)" ]; then
    echo "오류: ${REDIS_CONTAINER_NAME} 컨테이너가 실행 중이 아닙니다. yak-allim-infra에서 'docker compose up -d redis'를 먼저 실행하세요." >&2
    exit 1
fi

# 1. 배포 디렉터리 생성 및 키 준비
mkdir -p "${DEPLOY_DIR}"
rm -f "${DEPLOY_DIR}/yak-allim-firebase-key.json" 2>/dev/null || true

if [ -n "$FIREBASE_KEY_FILE" ] && [ -f "$FIREBASE_KEY_FILE" ]; then
    cp "$FIREBASE_KEY_FILE" "${DEPLOY_DIR}/yak-allim-firebase-key.json"
fi

# 2. Docker 이미지 빌드
echo "=== 1. Docker Image Build ==="
docker build -t "${IMAGE_NAME}" .

# 3. 블루-그린 포트 및 컨테이너 이름 결정 (8082 <-> 8083)
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

# 4. 대상 명시적 이름 및 포트 점유 컨테이너 정리
MY_CONTAINER_ID=$(hostname 2>/dev/null || true)

docker stop "${TARGET_NAME}" 2>/dev/null || true
docker rm -f "${TARGET_NAME}" 2>/dev/null || true

PORT_OCCUPIED_CONTAINERS=$(docker ps -aq --filter "publish=${TARGET_PORT}" 2>/dev/null || true)
if [ -n "$PORT_OCCUPIED_CONTAINERS" ]; then
    for cid in $PORT_OCCUPIED_CONTAINERS; do
        if [ -n "$MY_CONTAINER_ID" ] && [ "$cid" = "$MY_CONTAINER_ID" ]; then
            echo "Warning: Jenkins 컨테이너가 Target Port(${TARGET_PORT})를 사용 중이므로 정리 대상에서 제외합니다."
            continue
        fi
        echo "=== Target Port(${TARGET_PORT}) 점유 컨테이너($cid) 정리 중... ==="
        docker rm -f "$cid" 2>/dev/null || true
    done
fi

# 5. 신규 컨테이너 생성 및 자격 증명 파일 주입
docker create \
    --name "${TARGET_NAME}" \
    --restart unless-stopped \
    --network app-network \
    -p "${TARGET_PORT}:8081" \
    -e OCR_N8N_WEBHOOK_SECRET="${OCR_N8N_WEBHOOK_SECRET}" \
    "${IMAGE_NAME}" \
    --server.port=8081 \
    --notification.firebase.key-path="file:/app/yak-allim-firebase-key.json" \
    --ocr.n8n.webhook-url="${N8N_WEBHOOK_URL}" \
    --ocr.job-store=redis \
    --spring.data.redis.host="${REDIS_CONTAINER_NAME}" \
    --management.health.redis.enabled=true

# 앱은 비root 사용자(APP_UID)로 실행되므로, 키 파일 소유자를 맞춰 권한(0400)을 유지한 채 복사한다.
if [ -f "${DEPLOY_DIR}/yak-allim-firebase-key.json" ]; then
    chown "${APP_UID}:${APP_UID}" "${DEPLOY_DIR}/yak-allim-firebase-key.json"
    docker cp -a "${DEPLOY_DIR}/yak-allim-firebase-key.json" "${TARGET_NAME}:/app/yak-allim-firebase-key.json"
fi

docker start "${TARGET_NAME}"

# 6. Spring Boot Actuator HTTP 헬스 체크 진행
HEALTH_SUCCESS=false
echo "=== 신규 컨테이너(${TARGET_NAME}) Actuator HTTP 헬스 체크 진행 중... ==="

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
    echo "Spring Boot 구동 확인 중... (HTTP Status: ${HTTP_CODE:-000}, 시도 $retry/40)"
done

if [ "$HEALTH_SUCCESS" = "true" ]; then
    echo "=== 신규 컨테이너(${TARGET_NAME}) 정상 구동 완료 (PORT: ${TARGET_PORT}) ==="
    docker logs --tail 25 "${TARGET_NAME}"

    # 7. Nginx 포트 스위칭
    echo "=== Nginx 포트 스위칭 (Target: ${TARGET_NAME} / Port: ${TARGET_PORT}) 진행 ==="
    docker exec yak-allim-nginx sh -c "printf 'set \x24service_url http://%s:8081;\n' ${TARGET_NAME} > /etc/nginx/conf.d/service-url.inc"

    echo "=== NGINX 설정 재설정 ==="
    docker exec yak-allim-nginx nginx -s reload

    # 8. 이전 구버전 컨테이너 드레이닝 후 정지 및 삭제
    # 작업 상태는 Redis에 있어 두 컨테이너가 함께 보므로, 구버전이 받은 작업의 n8n 콜백을 신버전이 받아도 이어진다.
    # 드레이닝은 구버전에 연결된 SSE와 n8n으로 보내는 중인 요청이 끝날 시간을 주기 위한 것이다.
    OLD_CONTAINER_ID=$(docker ps --filter "name=^/${OLD_CONTAINER_NAME}$" --filter "status=running" -q 2>/dev/null || true)
    if [ -n "$OLD_CONTAINER_ID" ]; then
        if [ -n "$MY_CONTAINER_ID" ] && [ "$OLD_CONTAINER_ID" = "$MY_CONTAINER_ID" ]; then
            echo "Warning: Jenkins 컨테이너는 구버전 정지 대상에서 제외합니다."
        else
            echo "=== 이전 구버전 컨테이너(${OLD_CONTAINER_NAME}) 드레이닝 대기 (${OLD_CONTAINER_DRAIN_WAIT_SECONDS}초)... ==="
            sleep "${OLD_CONTAINER_DRAIN_WAIT_SECONDS}"
            echo "=== 이전 구버전 컨테이너(${OLD_CONTAINER_NAME}) graceful stop (타임아웃 ${OLD_CONTAINER_STOP_TIMEOUT_SECONDS}초) 및 정리 중... ==="
            docker stop -t "${OLD_CONTAINER_STOP_TIMEOUT_SECONDS}" "$OLD_CONTAINER_ID" 2>/dev/null || true
            docker rm -f "$OLD_CONTAINER_ID" 2>/dev/null || true
        fi
    fi

    # 9. 오래된 이미지 정리 (최근 IMAGE_RETENTION_COUNT개만 보존, 롤백용으로 남겨둠)
    echo "=== 오래된 ${IMAGE_REPOSITORY} 이미지 정리 (최근 ${IMAGE_RETENTION_COUNT}개 보존) ==="
    docker images "${IMAGE_REPOSITORY}" --format '{{.Tag}} {{.CreatedAt}}' \
        | grep -E '^[0-9]+ ' \
        | sort -k2 -r \
        | awk -v keep="${IMAGE_RETENTION_COUNT}" 'NR > keep { print $1 }' \
        | while read -r old_tag; do
            echo "삭제: ${IMAGE_REPOSITORY}:${old_tag}"
            docker rmi "${IMAGE_REPOSITORY}:${old_tag}" 2>/dev/null || true
        done
else
    echo "=== 신규 컨테이너(${TARGET_NAME}) 헬스 체크 실패 ==="
    docker logs --tail 30 "${TARGET_NAME}" 2>/dev/null || true
    docker rm -f "${TARGET_NAME}" 2>/dev/null || true
    exit 1
fi