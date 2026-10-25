# API 계약 (Yak-Allim Server)

서버와 안드로이드 클라이언트가 공유하는 REST/SSE/FCM 계약을 정리합니다. 이 문서와 실제 코드가
어긋나지 않도록, 서버 쪽은 `ApiContractTest`가 `PipelineStep` 값 목록과 주요 응답의 JSON 필드
이름을 검증하고, 안드로이드 쪽은 `OcrMappersTest`로 목록 일치를 검증합니다.

## 처리 흐름

1. 클라이언트가 `POST /enqueue`로 이미지를 올리면 서버가 작업을 등록하고 `202`로 바로 응답합니다.
2. 서버가 이미지를 n8n Webhook으로 전달합니다. n8n 워크플로우가 외부 OCR API와 LLM으로 복약 정보를 추출합니다.
3. n8n이 `POST /n8n/callback/{jobId}`로 결과(또는 실패)를 알립니다.
4. 서버가 작업 상태를 갱신하고 SSE와 FCM으로 클라이언트에 알립니다.

## REST 엔드포인트

Base path: `/api/v1/ocr`

| Method | Path                     | 요청                                                                  | 응답                                                     | 비고                          |
|--------|--------------------------|---------------------------------------------------------------------|--------------------------------------------------------|-----------------------------|
| POST   | `/enqueue`               | `multipart/form-data`: `file`(필수, jpg/jpeg/png), `fcmToken`(선택) | `202 Accepted` + `OcrJobResponse` / `400` / `413` / `503` | 작업을 등록하고 즉시 반환, 처리는 비동기     |
| GET    | `/jobs/{jobId}`          | -                                                                   | `200 OK` + `OcrJobResponse` / `404 Not Found`          | 작업 현재 상태 조회                 |
| GET    | `/jobs/{jobId}/progress` | -                                                                   | `200 OK`, `text/event-stream`                          | 아래 [SSE 이벤트](#sse-이벤트) 참고   |
| POST   | `/jobs/{jobId}/cancel`   | -                                                                   | `204 No Content` / `400 Bad Request` / `404 Not Found` | 진행 중인 작업 취소. 이미 종료된 작업은 400 |
| POST   | `/n8n/callback/{jobId}`  | 헤더 `X-N8N-Secret`, 본문 `N8nCallbackRequest`                          | `200 OK` / `400 Bad Request` / `401 Unauthorized`      | n8n 워크플로우 전용                |

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
- `dosagePerTake`, `dailyFrequency`, `durationDays`는 사진에 해당 숫자가 없거나 n8n이 추출하지 못하면 `null`입니다. 클라이언트는 `null`을 "확인 필요"로 보여 주고 사용자가 직접 입력하게 해야 합니다.
- 약품명과 세 값이 모두 같은 항목은 하나로 합쳐집니다(예: 영수증 쪽 목록의 반복 줄). 용법이 다른 같은 약은 따로 남습니다.
- `medicineName`은 LLM 추출 결과를 그대로 전달합니다(앞뒤 공백만 정리). 서버는 약품명을 교정하지 않으므로, 클라이언트는 원본 사진과 함께 보여주고 사용자가 확인하도록 안내하는 것을 권장합니다.
- `error`에는 내부 원인(HTTP 상태 코드, n8n 오류 등)이 아니라 사용자에게 그대로 보여줄 수 있는 메시지가 들어갑니다.

`status`(`JobStatus`)는 작업의 거친 상태이고, 진행 표시는 SSE의 `PipelineStep`을 참고하세요.

### 오류 응답 (`ErrorResponse`)

4xx/5xx 응답의 본문은 아래 형식입니다. `message`는 사용자에게 그대로 보여줄 수 있는 문장입니다.

```json
{
  "status": 400,
  "error": "Bad Request",
  "message": "필수 요청 값이 없습니다: file",
  "timestamp": "2026-10-17T00:00:00"
}
```

| 상태 | 발생 상황 |
| --- | --- |
| `400` | 필수 값 누락(`file` 등), 빈 파일, 허용하지 않는 확장자, 본문 JSON 형식 오류, 본문 검증 실패, 이미 종료된 작업 취소 |
| `401` | n8n 콜백의 `X-N8N-Secret` 불일치 |
| `404` | 존재하지 않는 작업 또는 경로 |
| `405` | 지원하지 않는 HTTP 메서드 |
| `413` | 업로드 크기 제한(10MB) 초과 |
| `415` | 지원하지 않는 `Content-Type`(예: 업로드를 `multipart/form-data`가 아닌 형식으로 전송) |
| `503` | 서버 대기열이 가득 참. 잠시 후 다시 시도 |
| `500` | 그 외 서버 내부 오류 |

`GET /jobs/{jobId}`의 `404`, 콜백의 `jobId` 불일치 `400`은 본문 없이 응답합니다.
nginx를 거칠 때 10MB를 넘는 업로드는 nginx가 먼저 막아 HTML `413`을 반환할 수 있습니다.

## SSE 이벤트

`GET /jobs/{jobId}/progress`

| 이벤트 이름                 | 발생 시점         | payload                                                    |
|------------------------|---------------|------------------------------------------------------------|
| `connect`              | 구독 시작 시 1회    | 단순 텍스트 (`"Connected to progress stream for job: {jobId}"`) |
| `progress`             | 진행 단계가 바뀔 때마다 | `OcrProgressResponse` (아래)                                 |
| (comment) `keep-alive` | 15초마다         | SSE 주석(named event 아님, 연결 유지용)                             |

이미 종료된 작업을 구독하면 `connect` 직후 최종 `progress` 이벤트 하나를 보내고 스트림을 닫습니다.
진행 중인 작업이면 작업 상태에 맞는 현재 단계를 먼저 한 번 보내 줍니다(`ACCEPTED` 상태면 `ACCEPTED`, `PROCESSING` 상태면 `ANALYZING`).
블루-그린 전환 중에는 콜백을 받은 서버와 구독 중인 서버가 다를 수 있지만, 진행 이벤트는 모든 서버에 전달되므로 그대로 받습니다.

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

| 값           | 기본 메시지             | 기본 progress(%) | 발행 시점                         |
|-------------|--------------------|----------------|-------------------------------|
| `ACCEPTED`  | 작업이 대기열에 등록되었습니다.  | 5              | 업로드 직후                        |
| `ANALYZING` | 복약 안내서를 분석하고 있습니다. | 30             | n8n이 요청을 받은 직후                |
| `COMPLETED` | 분석이 완료되었습니다.       | 100            | 성공 콜백 수신                      |
| `FAILED`    | 분석에 실패하였습니다.       | 100            | n8n 전송 실패, 실패 콜백 수신, 처리 시간 초과 |
| `CANCELLED` | 작업이 취소되었습니다.       | 100            | 취소 요청 처리                      |

서버는 n8n 워크플로우 내부의 세부 진행 상황을 알 수 없습니다. `ANALYZING` 동안에는 `progress`
숫자 대신 무한 진행 표시(indeterminate)를 쓰는 것을 권장합니다.

## FCM 알림 payload

`POST /enqueue`에 `fcmToken`을 전달한 경우, 작업 완료/실패 시 FCM data 메시지를 보냅니다.
취소한 작업에는 알림을 보내지 않습니다.

| status      | data 키                                             | 설명                |
|-------------|----------------------------------------------------|-------------------|
| `COMPLETED` | `jobId`, `status="COMPLETED"`, `message`           | 완료 메시지            |
| `FAILED`    | `jobId`, `status="FAILED"`, `errorCode`, `message` | 아래 `errorCode` 참고 |

| errorCode                | 원인                                                               |
|--------------------------|------------------------------------------------------------------|
| `N8N_DISPATCH_FAILED`    | 서버가 n8n으로 요청을 보내지 못함(연결 실패, n8n이 요청 거부 등)                        |
| `N8N_PROCESSING_FAILED`  | n8n 워크플로우가 분석에 실패했다고 콜백으로 알림                                     |
| `OCR_PROCESSING_TIMEOUT` | 처리 시간(`ocr.job-processing-timeout-minutes`, 기본 15분) 안에 콜백이 오지 않음, 또는 n8n으로 보내기 전 대기 시간(`ocr.job-accepted-timeout-minutes`, 기본 5분)을 넘김 |

> ⚠️ **클라이언트 구현 시 주의**: 서버는 에러 텍스트를 `"message"` 키로 보냅니다. `"error"`라는
> 키는 보내지 않습니다.

## n8n 연동 계약

워크플로우 쪽 설정 방법(에러 출력, 실패 콜백 노드 등)은
[`yak-allim-infra`의 `n8n/README.md`](https://github.com/koolunkle-yak-allim/yak-allim-infra/blob/main/n8n/README.md)에 있습니다.

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

실패(워크플로우의 `Spring Boot (실패)` 노드가 각 노드의 에러 출력이나 약품 추출 실패 시 호출):

```json
{
  "jobId": "string (필수)",
  "status": "FAILED",
  "error": "실패 원인 (선택, 서버 로그에만 기록)"
}
```

- `status`가 `FAILED`(대소문자 무관)이면 실패로, 그 외 값이거나 생략하면 성공으로 처리합니다.
- `medicineName`이 비어 있는 항목은 결과에서 제외됩니다.
- 약품명(앞뒤 공백 정리 후)·`dosagePerTake`·`dailyFrequency`·`durationDays`가 모두 같은 항목은 하나만 남깁니다. 순서는 처음 나온 순서를 따릅니다.
