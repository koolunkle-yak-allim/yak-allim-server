# Yak-Allim-Server

> **복약 안내서 OCR 분석 기반 복약 관리 솔루션**

**Yak-Allim-Server**는 클라이언트가 올린 복약 안내서 이미지를 n8n 워크플로우(외부 OCR API + LLM)에 넘겨 복약 정보를 추출하고, 그 결과를 작업 상태·SSE·FCM으로 클라이언트에 전달하는 Spring Boot 기반의 API 서버입니다.

> 서버에서 ONNX Runtime(PP-OCRv4)으로 직접 추론하던 이전 구현은 [`archive/onnx-local-ocr`](https://github.com/koolunkle-yak-allim/yak-allim-server/tree/archive/onnx-local-ocr) 브랜치에 보존되어 있습니다.

---

## Features

- **n8n Workflow Integration**: 업로드된 이미지를 n8n Webhook으로 전달하고, n8n 워크플로우가 외부 OCR API와 LLM으로 추출한 복약 정보를 콜백(`POST /api/v1/ocr/n8n/callback/{jobId}`)으로 받습니다. 양방향 요청은 공유 secret으로 인증합니다.
- **Asynchronous Job Management**: 요청 수신 시 작업 ID와 함께 Accepted(202)를 즉시 반환하고, 작업 상태 조회·취소·처리 시간 초과·SSE 진행률 스트림을 제공합니다.
- **FCM Notification**: 분석 작업이 완료되거나 실패했을 때 Firebase Cloud Messaging(FCM)을 통해 클라이언트에 알림을 전송합니다.

---

## Tech Stack

- **Framework**: Spring Boot 3.5.15
- **Language**: Kotlin 2.0.21 (Coroutines)
- **Libraries**:
  - Firebase Admin SDK 9.2.0
  - Ktor Client (n8n Webhook 비동기 HTTP 통신)
  - SpringDoc OpenAPI(Swagger UI)
- **Testing**: JUnit 5, Mockito-Kotlin
- **Build**: Gradle (Kotlin DSL)

---

## Project Structure

패키지를 레이어가 아닌 **기능(도메인) 단위**로 우선 분리하고(`notification`/`ocr`), 각 기능 패키지 내부는 Spring의 통상적인 계층(`controller`/`service`/`repository`/`config` 등)으로 나눴습니다.

### Package Structure

```text
com.example.yakallim
├── global                 # 공통 전역 처리
│   ├── config             # Swagger / OpenApi, CORS 등 전역 설정
│   └── exception          # 전역 예외 처리(@RestControllerAdvice) 및 공통 ErrorResponse
├── notification            # 알림 발송
│   ├── config              # FCM 설정
│   ├── infrastructure      # Firebase FCM 구현체
│   └── service              # NotificationClient 인터페이스
└── ocr                     # 복약 안내서 분석 작업 (핵심 도메인)
    ├── config              # n8n 연동/비동기 실행기 설정 프로퍼티
    ├── controller          # REST API 컨트롤러
    ├── dto                 # 요청/응답 DTO
    ├── exception           # OCR 도메인 전용 예외
    ├── infrastructure      # n8n Webhook Client
    ├── model               # OCR 도메인 모델(PipelineStep, PrescribedMedicine 등)
    ├── repository          # 작업 상태 저장소(InMemoryOcrJobRepository)
    └── service             # 작업 접수·콜백 처리, 진행률 SSE, 정리 스케줄러
```

API 엔드포인트·SSE 이벤트·FCM 페이로드·n8n 콜백 등 서버-클라이언트 계약은 [`docs/api-contract.md`](docs/api-contract.md)에 별도로 정리되어 있습니다.
n8n 워크플로우와 실패 콜백 설정 방법은 [`yak-allim-infra`의 `n8n/README.md`](https://github.com/koolunkle-yak-allim/yak-allim-infra/blob/main/n8n/README.md)를 참고하세요.

---

## Getting Started

### Prerequisites

- **JDK**: Java 17
- **External Keys**: Firebase Admin SDK 비공개 키 JSON 파일 (`yak-allim-firebase-key.json`)

### Configuration

1. Firebase Console에서 발급받은 서비스 계정 키 파일의 이름을 `yak-allim-firebase-key.json`으로 변경하여 백엔드 프로젝트 루트 디렉터리에 배치합니다.
2. `src/main/resources/application.properties` 파일에서 n8n Webhook 주소를 지정합니다. 서버는 이미지를 이 주소로 전달하고, n8n 워크플로우가 분석 후 콜백(`POST /api/v1/ocr/n8n/callback/{jobId}`)합니다:

   ```properties
   ocr.n8n.webhook-url=http://localhost:5678/webhook-test/ocr
   ```

   `OCR_N8N_WEBHOOK_SECRET` 환경 변수가 반드시 필요합니다(비어 있으면 기동에 실패합니다).
   이 값은 서버 → n8n 요청에는 `X-N8N-WEBHOOK-SECRET` 헤더로, n8n → 서버 콜백에는 `X-N8N-Secret` 헤더로
   전달됩니다(양방향 헤더 이름이 다르니 n8n 워크플로우 설정 시 유의하세요).

### Installation & Build

1. 저장소를 복제합니다:

   ```bash
   git clone https://github.com/koolunkle-yak-allim/yak-allim-server.git
   ```

2. 백엔드 프로젝트 루트에 `yak-allim-firebase-key.json` 파일을 추가하고, `OCR_N8N_WEBHOOK_SECRET` 환경 변수를 설정합니다.
3. 프로젝트를 빌드하고 실행합니다:

   ```bash
   ./gradlew bootRun
   ```

---

## Known Limitations

현재 알려진 제약사항입니다. 각 항목은 순차적으로 개선할 계획입니다.

- **약품명 검증 없음**: 서버는 LLM이 추출한 약품명을 교정하거나 공식 의약품 데이터와 대조하지 않고 그대로 전달합니다. 클라이언트에서 원본 사진과 함께 보여주고 사용자가 확인하도록 안내해야 합니다.
- **작업 상태 영속성 없음**: 작업 상태가 메모리(`ConcurrentHashMap`)에만 저장되어 서버 재시작 시 모두 사라지고, 재시작 전에 보낸 요청의 n8n 콜백은 받을 곳이 없어집니다. n8n 콜백이 오지 않으면 처리 시간 초과(`ocr.job-processing-timeout-minutes`, 기본 15분) 시 자동으로 `FAILED` 처리됩니다.
- **n8n 의존성**: 분석 전체가 n8n 워크플로우와 그 뒤의 외부 OCR API·LLM에 의존합니다. n8n 워크플로우가 실패 콜백을 보내지 못하고 멈추면, 서버는 이를 처리 시간 초과로만 감지합니다. 처방전 이미지가 외부 서비스로 전송된다는 점도 유의해야 합니다.
- **세부 진행률 없음**: 서버는 n8n 내부의 단계별 진행 상황을 알 수 없어, 분석 중에는 `ANALYZING` 단계 하나로만 표시합니다.
- **비동기 실행기 큐 한도**: n8n 전송은 전용 스레드풀(`ocrTaskExecutor`, 동시 실행 2개·대기열 20개)을 쓰며, 큐가 가득 차면 업로드 파일을 정리하고 503으로 응답합니다. 다만 큐 크기(20)는 실측 없이 정한 추정치라, 실제 트래픽 패턴에 맞춰 재조정이 필요합니다.
