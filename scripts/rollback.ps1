# 이전 빌드로 블루-그린 색상을 전환하는 롤백 스크립트 (Windows).
# 사용법: .\rollback.ps1 -Tag 42        (yak-allim-backend:42 로 롤백)
# Tag는 deploy.ps1이 이미지 정리 시 보존한(imageRetentionCount개) 태그여야 합니다.

param(
    [Parameter(Mandatory = $false)]
    [string]$Tag = $env:ROLLBACK_TAG
)

$ErrorActionPreference = 'Stop'

Write-Host "=== [Rollback Stage] Blue-Green Rollback Started (Windows) ==="

if (-not $Tag) {
    throw "오류: 롤백할 이미지 태그(빌드 번호)를 -Tag 파라미터 또는 ROLLBACK_TAG 환경 변수로 전달하세요. 예) .\rollback.ps1 -Tag 42"
}

$deployDir = if ($env:DEPLOY_DIR) { $env:DEPLOY_DIR } else { "$env:WORKSPACE\deploy" }
$imageRepository = if ($env:IMAGE_REPOSITORY) { $env:IMAGE_REPOSITORY } else { "yak-allim-backend" }
$imageName = "${imageRepository}:${Tag}"
$n8nWebhookUrl = if ($env:N8N_WEBHOOK_URL) { $env:N8N_WEBHOOK_URL } else { "http://yak-allim-n8n:5678/webhook/ocr" }
$oldContainerDrainWaitSeconds = if ($env:OLD_CONTAINER_DRAIN_WAIT_SECONDS) { [int]$env:OLD_CONTAINER_DRAIN_WAIT_SECONDS } else { 30 }
$oldContainerStopTimeoutSeconds = if ($env:OLD_CONTAINER_STOP_TIMEOUT_SECONDS) { [int]$env:OLD_CONTAINER_STOP_TIMEOUT_SECONDS } else { 70 }

$imageExists = docker image inspect $imageName 2>$null
if (-not $imageExists) {
    throw "오류: 로컬에 $imageName 이미지가 없습니다. (deploy.ps1의 이미지 정리 주기에 의해 이미 삭제되었을 수 있습니다)"
}

Write-Host "=== 롤백 대상 이미지: $imageName ==="

# 1. 블루-그린 포트 및 컨테이너 이름 결정 (8082 <-> 8083)
$ErrorActionPreference = 'SilentlyContinue'
$isBlueActive = docker ps --filter "name=^/yak-allim-backend-blue$" --filter "status=running" -q
$ErrorActionPreference = 'Stop'

if ($isBlueActive) {
    $targetPort = "8083"
    $targetName = "yak-allim-backend-green"
    $oldContainerName = "yak-allim-backend-blue"
} else {
    $targetPort = "8082"
    $targetName = "yak-allim-backend-blue"
    $oldContainerName = "yak-allim-backend-green"
}

Write-Host "=== Target 배포 설정 ==="
Write-Host "Target Port: $targetPort"
Write-Host "Target Container Name: $targetName"

$myContainerId = $env:COMPUTERNAME
$ErrorActionPreference = 'SilentlyContinue'
docker stop $targetName
docker rm -f $targetName
$ErrorActionPreference = 'Stop'

# 2. 롤백 대상 이미지로 신규(구버전) 컨테이너 생성 — 모델/키는 배포 디렉터리에 남아있는 것을 재사용
docker create `
    --name $targetName `
    --restart unless-stopped `
    --network app-network `
    -p "${targetPort}:8081" `
    $imageName `
    --server.port=8081 `
    --notification.firebase.key-path="file:/app/yak-allim-firebase-key.json" `
    --ocr.engine.onnx.detection-model-path="file:/app/models/ch_PP-OCRv4_det_infer.onnx" `
    --ocr.engine.onnx.recognition-model-path="file:/app/models/korean_PP-OCRv4_rec_infer.onnx" `
    --ocr.engine.onnx.recognition-dictionary-path="file:/app/models/korean_dict.txt" `
    --ocr.n8n.webhook-url="$n8nWebhookUrl"

$firebaseKeyPath = "$deployDir\yak-allim-firebase-key.json"
if (Test-Path $firebaseKeyPath) {
    docker cp $firebaseKeyPath "${targetName}:/app/yak-allim-firebase-key.json"
}

$modelsDir = "$deployDir\models"
if (Test-Path $modelsDir) {
    docker cp "$modelsDir" "${targetName}:/app/"
}

docker start $targetName

# 3. 헬스 체크
$healthSuccess = $false
Write-Host "=== 롤백 컨테이너(${targetName}) Actuator HTTP 헬스 체크 진행 중... ==="

for ($retry = 1; $retry -le 40; $retry++) {
    Start-Sleep -Seconds 4
    try {
        $res = Invoke-WebRequest -Uri "http://localhost:${targetPort}/actuator/health" -UseBasicParsing -TimeoutSec 2 -ErrorAction SilentlyContinue
        if ($res.StatusCode -eq 200) {
            $healthSuccess = $true
            break
        }
    } catch {
        Write-Host "롤백 컨테이너 구동 확인 중... (시도 $retry/40)"
    }
}

if (-not $healthSuccess) {
    Write-Host "=== 롤백 컨테이너(${targetName}) 헬스 체크 실패 — 롤백 중단 ==="
    docker logs --tail 30 $targetName
    $ErrorActionPreference = 'SilentlyContinue'
    docker rm -f $targetName
    $ErrorActionPreference = 'Stop'
    throw "롤백 컨테이너 구동 실패"
}

Write-Host "=== 롤백 컨테이너(${targetName}) 정상 구동 완료 (PORT: ${targetPort}) ==="
docker logs --tail 25 $targetName

# 4. Nginx 포트 스위칭
$deployIncPath = "$deployDir\service-url.inc"
Write-Host "=== Nginx 포트 스위칭 (Target: ${targetName} / Port: ${targetPort}) 진행 ==="
[System.IO.File]::WriteAllText("$deployIncPath", 'set $service_url http://' + $targetName + ':8081;')
docker exec yak-allim-nginx sh -c "printf 'set \x24service_url http://$targetName:8081;\n' > /etc/nginx/conf.d/service-url.inc"
$ErrorActionPreference = 'SilentlyContinue'
docker exec yak-allim-nginx nginx -s reload
$ErrorActionPreference = 'Stop'

# 5. 이전(방금 롤백으로 대체된) 컨테이너 드레이닝 후 정지 및 삭제
$ErrorActionPreference = 'SilentlyContinue'
$oldContainerId = docker ps --filter "name=^/${oldContainerName}$" --filter "status=running" -q
$ErrorActionPreference = 'Stop'
if ($oldContainerId) {
    if ($myContainerId -and ($oldContainerId -eq $myContainerId)) {
        Write-Host "Warning: Jenkins 컨테이너는 정지 대상에서 제외합니다."
    } else {
        Write-Host "=== 이전 컨테이너(${oldContainerName}) 드레이닝 대기 (${oldContainerDrainWaitSeconds}초)... ==="
        Start-Sleep -Seconds $oldContainerDrainWaitSeconds
        Write-Host "=== 이전 컨테이너(${oldContainerName}) graceful stop (타임아웃 ${oldContainerStopTimeoutSeconds}초) 및 정리 중... ==="
        $ErrorActionPreference = 'SilentlyContinue'
        docker stop -t $oldContainerStopTimeoutSeconds $oldContainerId
        docker rm -f $oldContainerId
        $ErrorActionPreference = 'Stop'
    }
}

Write-Host "=== 롤백 완료: $imageName ==="
