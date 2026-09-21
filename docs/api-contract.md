# API 계약 (Yak-Allim Server)

서버와 안드로이드 클라이언트가 공유하는 REST/SSE/FCM 계약을 정리합니다. 이 문서와 실제 코드가
어긋나지 않도록, 서버 쪽은 `PipelineStep.entries`가 아래 표와 같은지 확인하는 테스트
(`ApiContractTest`)로, 안드로이드 쪽은 `OcrMappersTest`(A1)로 목록 일치를 검증합니다.

## REST 엔드포인트

Base path: `/api/v1/ocr`

| Method | Path | 요청 | 응답 | 비고 |
| --- | --- | --- | --- | --- |
| POST | `/enqueue` | `multipart/form-data`: `file`(필수), `fcmToken`(선택), `delay`(선택, ms) | `202 Accepted` + `OcrJobResponse` | 작업을 큐에 등록하고 즉시 반환, 처리는 비동기 |
| GET | `/jobs/{jobId}` | - | `200 OK` + `OcrJobResponse` / `404 Not Found` | 작업 현재 상태 조회 |
| GET | `/jobs/{jobId}/progress` | - | `200 OK`, `text/event-stream` | 아래 [SSE 이벤트](#sse-이벤트) 참고 |
| POST | `/jobs/{jobId}/cancel` | - | `204 No Content` / `404 Not Found` | 진행 중인 작업 취소 요청 |
| POST | `/n8n/callback/{jobId}` | 헤더 `X-N8N-Secret`(선택), 본문 `N8nCallbackRequest` | `200 OK` / `400 Bad Request` / `401 Unauthorized` | n8n 워크플로우가 분석 결과를 콜백 |

### `OcrJobResponse`

```json
{
  "jobId": "string",
  "status": "ACCEPTED | PROCESSING | COMPLETED | FAILED | CANCELLED",
  "result": { "...": "완료 시 OcrResponse(fileName, message, textBlocks, medicines)" },
  "error": "실패 시 에러 메시지 (string, nullable)"
}
```

`status`(`JobStatus`)는 작업의 **거친(coarse) 상태**이며, SSE로 전달되는 `PipelineStep`(아래)보다
단계가 적습니다. 완료/실패까지의 세부 진행 단계는 SSE 쪽을 참고하세요.

## SSE 이벤트 (`GET /jobs/{jobId}/progress`)

| 이벤트 이름 | 발생 시점 | payload |
| --- | --- | --- |
| `connect` | 구독 시작 시 1회 | 단순 텍스트 (`"Connected to progress stream for job: {jobId}"`) |
| `progress` | 진행 단계가 바뀔 때마다 | `OcrProgressResponse` (아래) |
| (comment) `keep-alive` | 15초마다 | SSE 주석(named event 아님, 연결 유지용) |

### `OcrProgressResponse`

```json
{
  "step": "ACCEPTED | IMAGE_PROCESSING | TEXT_DETECTION | TEXT_RECOGNITION | PARSING | COMPLETED | FAILED",
  "message": "string",
  "progress": 0,
  "isFinished": false
}
```

### `PipelineStep` 값 목록

| 값 | 기본 메시지 | 기본 progress(%) |
| --- | --- | --- |
| `ACCEPTED` | 작업이 대기열에 등록되었습니다. | 5 |
| `IMAGE_PROCESSING` | 이미지 전처리 중... | 15 |
| `TEXT_DETECTION` | 텍스트 영역 검출 중... | 35 |
| `TEXT_RECOGNITION` | 텍스트 인식 중... | 65 |
| `PARSING` | 처방 정보 분석 및 의약품 매칭 중... | 85 |
| `COMPLETED` | 분석이 완료되었습니다. | 100 |
| `FAILED` | 분석에 실패하였습니다. | 100 |

`n8n` 모드에서는 `IMAGE_PROCESSING`/`TEXT_DETECTION`/`TEXT_RECOGNITION` 3단계가 n8n 워크플로우의
실제 처리 단계가 아니라 요청 전송 전후에 발행되는 **표시용** 진행률입니다(S8 참고).

## FCM 알림 payload

`POST /enqueue`에 `fcmToken`을 전달한 경우, 작업 완료/실패 시 FCM data 메시지를 보냅니다.

| status | data 키 | 설명 |
| --- | --- | --- |
| `COMPLETED` | `jobId`, `status="COMPLETED"`, `message` | 완료 메시지 |
| `FAILED` | `jobId`, `status="FAILED"`, `errorCode`, `message` | `errorCode`는 `OCR_PROCESSING_FAILED` 또는 `N8N_DISPATCH_FAILED` |

> ⚠️ **클라이언트 구현 시 주의**: 서버는 에러 텍스트를 `"message"` 키로 보냅니다. `"error"`라는
> 키는 보내지 않습니다. (현재 안드로이드 `FcmPayloadSpec.KEY_ERROR = "error"`로 이 값을 읽으려
> 하고 있어 실제로는 항상 비어 있습니다 — 클라이언트 쪽 수정이 필요합니다.)

## n8n 콜백 계약 (`POST /n8n/callback/{jobId}`)

- 요청 헤더: `X-N8N-Secret` — 서버가 `ocr.n8n.webhook-secret`을 설정한 경우 필수. 값이 일치하지
  않으면 인증 실패로 거부됩니다.
- 서버 → n8n 요청 시에는 반대로 `X-N8N-WEBHOOK-SECRET` 헤더를 사용합니다(양방향 헤더 이름이
  다름에 유의).
- 요청 본문(`N8nCallbackRequest`):

```json
{
  "jobId": "string (필수)",
  "status": "string (참고용, 서버는 현재 사용하지 않음)",
  "data": {
    "medicines": [
      {
        "medicineName": "string",
        "dosagePerTake": "string?",
        "dailyFrequency": 0,
        "durationDays": 0,
        "bounds": []
      }
    ]
  }
}
```

- n8n은 의약품명 정규화(오타 교정)를 거치지 않은 원문을 그대로 보내야 합니다. 서버가 별도
  교정을 적용하지 않습니다(S8 참고).
