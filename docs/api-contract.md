# API 계약 (Yak-Allim Server)

서버와 안드로이드 클라이언트가 공유하는 REST/SSE/FCM 계약을 정리합니다. 이 문서와 실제 코드가
어긋나지 않도록, 서버 쪽은 `ApiContractTest`가 `PipelineStep` 값 목록과 주요 응답의 JSON 필드
이름을 검증하고, 안드로이드 쪽은 `OcrMappersTest`(A1)로 목록 일치를 검증합니다.

## 처리 흐름

1. 클라이언트가 `POST /enqueue`로 이미지를 올리면 서버가 작업을 등록하고 `202`로 바로 응답합니다.
2. 서버가 이미지를 n8n Webhook으로 전달합니다. n8n 워크플로우가 외부 OCR API와 LLM으로 복약 정보를 추출합니다.
3. n8n이 `POST /n8n/callback/{jobId}`로 결과(또는 실패)를 알립니다.
4. 서버가 작업 상태를 갱신하고 SSE와 FCM으로 클라이언트에 알립니다.

## REST 엔드포인트

Base path: `/api/v1/ocr`

| Method | Path | 요청 | 응답 | 비고 |
| --- | --- | --- | --- | --- |
| POST | `/enqueue` | `multipart/form-data`: `file`(필수, jpg/png/gif/webp), `fcmToken`(선택) | `202 Accepted` + `OcrJobResponse` | 작업을 등록하고 즉시 반환, 처리는 비동기 |
| GET | `/jobs/{jobId}` | - | `200 OK` + `OcrJobResponse` / `404 Not Found` | 작업 현재 상태 조회 |
| GET | `/jobs/{jobId}/progress` | - | `200 OK`, `text/event-stream` | 아래 [SSE 이벤트](#sse-이벤트) 참고 |
| POST | `/jobs/{jobId}/cancel` | - | `204 No Content` / `400 Bad Request` / `404 Not Found` | 진행 중인 작업 취소. 이미 종료된 작업은 400 |
| POST | `/n8n/callback/{jobId}` | 헤더 `X-N8N-Secret`, 본문 `N8nCallbackRequest` | `200 OK` / `400 Bad Request` / `401 Unauthorized` | n8n 워크플로우 전용 |

### `OcrJobResponse`

```json
{
  "jobId": "string",
  "status": "ACCEPTED | PROCESSING | COMPLETED | FAILED | CANCELLED",
  "result": {
    "message": "복약 안내서 분석이 완료되었습니다.\n복약 지침을 확인해 보세요.",
    "medicines": [
      {
        "medicineName": "string",
        "dosagePerTake": "string | null",
        "dailyFrequency": 0,
        "durationDays": 0
      }
    ]
  },
  "error": "실패 시 사용자에게 보여줄 메시지 (string, nullable)"
}
```

- `result`는 `COMPLETED`일 때만 채워집니다.
- `dosagePerTake`, `dailyFrequency`, `durationDays`는 n8n이 추출하지 못하면 `null`입니다.
- `medicineName`은 LLM 추출 결과를 그대로 전달합니다(앞뒤 공백만 정리). 서버는 약품명을 교정하지 않으므로, 클라이언트는 원본 사진과 함께 보여주고 사용자가 확인하도록 안내하는 것을 권장합니다.
- `error`에는 내부 원인(HTTP 상태 코드, n8n 오류 등)이 아니라 사용자에게 그대로 보여줄 수 있는 메시지가 들어갑니다.

`status`(`JobStatus`)는 작업의 거친 상태이고, 진행 표시는 SSE의 `PipelineStep`을 참고하세요.

## SSE 이벤트

`GET /jobs/{jobId}/progress`

| 이벤트 이름 | 발생 시점 | payload |
| --- | --- | --- |
| `connect` | 구독 시작 시 1회 | 단순 텍스트 (`"Connected to progress stream for job: {jobId}"`) |
| `progress` | 진행 단계가 바뀔 때마다 | `OcrProgressResponse` (아래) |
| (comment) `keep-alive` | 15초마다 | SSE 주석(named event 아님, 연결 유지용) |

이미 종료된 작업을 구독하면 `connect` 직후 최종 `progress` 이벤트 하나를 보내고 스트림을 닫습니다.
진행 중인 작업이면 마지막으로 발행된 단계를 먼저 한 번 보내 줍니다.

### `OcrProgressResponse`

```json
{
  "step": "ACCEPTED | ANALYZING | COMPLETED | FAILED | CANCELLED",
  "message": "string",
  "progress": 0,
  "isFinished": false
}
```

`isFinished`가 `true`이면(`COMPLETED`/`FAILED`/`CANCELLED`) 서버가 스트림을 닫습니다.

### `PipelineStep` 값 목록

| 값 | 기본 메시지 | 기본 progress(%) | 발행 시점 |
| --- | --- | --- | --- |
| `ACCEPTED` | 작업이 대기열에 등록되었습니다. | 5 | 업로드 직후 |
| `ANALYZING` | 복약 안내서를 분석하고 있습니다. | 30 | n8n이 요청을 받은 직후 |
| `COMPLETED` | 분석이 완료되었습니다. | 100 | 성공 콜백 수신 |
| `FAILED` | 분석에 실패하였습니다. | 100 | n8n 전송 실패, 실패 콜백 수신, 처리 시간 초과 |
| `CANCELLED` | 작업이 취소되었습니다. | 100 | 취소 요청 처리 |

서버는 n8n 워크플로우 내부의 세부 진행 상황을 알 수 없습니다. `ANALYZING` 동안에는 `progress`
숫자 대신 무한 진행 표시(indeterminate)를 쓰는 것을 권장합니다.

## FCM 알림 payload

`POST /enqueue`에 `fcmToken`을 전달한 경우, 작업 완료/실패 시 FCM data 메시지를 보냅니다.
취소한 작업에는 알림을 보내지 않습니다.

| status | data 키 | 설명 |
| --- | --- | --- |
| `COMPLETED` | `jobId`, `status="COMPLETED"`, `message` | 완료 메시지 |
| `FAILED` | `jobId`, `status="FAILED"`, `errorCode`, `message` | 아래 `errorCode` 참고 |

| errorCode | 원인 |
| --- | --- |
| `N8N_DISPATCH_FAILED` | 서버가 n8n으로 요청을 보내지 못함(연결 실패, n8n이 요청 거부 등) |
| `N8N_PROCESSING_FAILED` | n8n 워크플로우가 분석에 실패했다고 콜백으로 알림 |
| `OCR_PROCESSING_TIMEOUT` | 처리 시간(`ocr.job-processing-timeout-minutes`, 기본 15분) 안에 콜백이 오지 않음 |

> ⚠️ **클라이언트 구현 시 주의**: 서버는 에러 텍스트를 `"message"` 키로 보냅니다. `"error"`라는
> 키는 보내지 않습니다.

## n8n 연동 계약

### 서버 → n8n

- `POST {ocr.n8n.webhook-url}?jobId={jobId}`, `multipart/form-data`의 `file` 필드에 이미지를 담습니다.
- 헤더 `X-N8N-WEBHOOK-SECRET`: `OCR_N8N_WEBHOOK_SECRET` 값.
- n8n은 요청을 받으면 바로 2xx로 응답하고, 분석은 비동기로 진행한 뒤 콜백해야 합니다.
  2xx가 아닌 응답은 전송 실패(`N8N_DISPATCH_FAILED`)로 처리됩니다.

### n8n → 서버 콜백

`POST /api/v1/ocr/n8n/callback/{jobId}`

- 헤더 `X-N8N-Secret`: `OCR_N8N_WEBHOOK_SECRET` 값. 일치하지 않으면 `401`입니다.
  서버 → n8n 요청과 헤더 이름이 다르니 유의하세요.
- 경로의 `jobId`와 본문의 `jobId`가 다르면 `400`입니다.
- 이미 종료된 작업(취소, 시간 초과 등)에 도착한 콜백은 `200`으로 응답하되 무시합니다.

성공:

```json
{
  "jobId": "string (필수)",
  "status": "COMPLETED",
  "data": {
    "medicines": [
      { "medicineName": "string (필수)", "dosagePerTake": "string?", "dailyFrequency": 0, "durationDays": 0 }
    ]
  }
}
```

실패(워크플로우의 Error Trigger 등에서 호출):

```json
{
  "jobId": "string (필수)",
  "status": "FAILED",
  "error": "실패 원인 (선택, 서버 로그에만 기록)"
}
```

- `status`가 `FAILED`(대소문자 무관)이면 실패로, 그 외 값이거나 생략하면 성공으로 처리합니다.
- `medicineName`이 비어 있는 항목은 결과에서 제외됩니다.
