# Yak-Allim-Server

> **복약 안내서 OCR 분석 기반 복약 관리 솔루션**

**Yak-Allim-Server**는 복약 안내서 이미지에서 텍스트를 검출 및 인식하고, 복약 정보를 추출하여 클라이언트에 제공하는 Spring Boot 기반의 API 서버입니다.

---

## Features

- **Hybrid OCR Engine Support**: 로컬 ONNX Runtime 기반 PP-OCRv4 엔진뿐만 아니라 n8n 워크플로우 Webhook 연동을 통한 하이브리드 OCR 엔진 선택을 지원합니다 (`ocr.type=local` 또는 `ocr.type=n8n`).
- **Server-side Local OCR Inference**: ONNX Runtime을 활용하여 서버 측에서 PP-OCRv4 모델을 바로 실행할 수 있습니다.
- **Image Correction & Parsing**: 이미지 기울기 보정, 텍스트 컬럼 분리 및 문자 간격 기반 세그멘테이션을 적용하여 텍스트 위치와 정렬을 보정합니다.
- **Medicine Name Normalization**: 자모 분해 및 Levenshtein Distance(편집 거리) 알고리즘을 적용하여, OCR 인식 결과와 로컬 의약품 사전 데이터를 비교 분석하고 유사한 명칭으로 정규화합니다.
- **Asynchronous Processing & Webhook Callback**: OCR 요청 수신 시 작업 ID와 함께 Accepted(202)를 즉시 반환하며, 백그라운드 스레드 추론 또는 n8n Webhook 처리 후 콜백(`POST /api/v1/ocr/n8n/callback/{jobId}`)을 수신하여 비동기로 완료 처리합니다.
- **FCM Notification**: 분석 작업이 완료되거나 실패했을 때 Firebase Cloud Messaging(FCM)을 통해 클라이언트에 알림을 전송합니다.

---

## Tech Stack

- **Framework**: Spring Boot 3.5.15
- **Language**: Kotlin 2.0.21 (Coroutines)
- **Database**: H2 Database (In-Memory), Spring Data JPA
- **Libraries**:
  - ONNX Runtime 1.18.0
  - Firebase Admin SDK 9.2.0
  - Ktor Client (n8n Webhook 비동기 HTTP 통신)
  - SpringDoc OpenAPI(Swagger UI)
- **Testing**: JUnit 5, Mockito-Kotlin
- **Build**: Gradle (Kotlin DSL)

---

## Project Structure

패키지를 레이어가 아닌 **기능(도메인) 단위**로 우선 분리하고(`medicine`/`notification`/`ocr`), 각 기능 패키지 내부는 Spring의 통상적인 계층(`controller`/`service`/`repository`/`config` 등)으로 나눴습니다.

### Package Structure

```text
com.example.yakallim
├── global                 # 공통 전역 처리
│   ├── config             # Swagger / OpenApi, CORS 등 전역 설정
│   ├── exception          # 전역 예외 처리(@RestControllerAdvice) 및 공통 ErrorResponse
│   └── utils              # 한글 자모 분해 등 유틸리티
├── medicine                # 의약품 사전 데이터 관리
│   ├── config              # 매칭 규칙 관련 설정 프로퍼티
│   ├── initializer         # CSV 사전 데이터 초기 적재
│   ├── model                # 의약품 도메인 모델
│   ├── repository          # 의약품 Repository
│   └── service              # 약품명 정규화(오타 교정) 서비스
├── notification            # 알림 발송
│   ├── config              # FCM 설정
│   ├── infrastructure      # Firebase FCM 구현체
│   └── service              # NotificationClient 인터페이스
└── ocr                     # OCR 추론 및 분석 (핵심 도메인)
    ├── config              # OCR/파서/비동기 실행기 설정 프로퍼티
    ├── controller          # REST API 컨트롤러
    ├── dto                 # 요청/응답 DTO
    ├── engine              # ONNX 로컬 추론 엔진, n8n Webhook Client
    ├── exception           # OCR 도메인 전용 예외
    ├── model               # OCR 도메인 모델(PipelineStep, PrescribedMedicine 등)
    ├── parser              # OCR 결과를 복용 정보로 구조화하는 파서
    ├── repository          # 작업 상태 저장소(InMemoryOcrJobRepository)
    └── service             # 비동기 작업 처리, 진행률 SSE, 정리 스케줄러
```

API 엔드포인트·SSE 이벤트·FCM 페이로드 등 서버-클라이언트 계약은 [`docs/api-contract.md`](docs/api-contract.md)에 별도로 정리되어 있습니다.

---

## Getting Started

### Prerequisites

- **JDK**: Java 17
- **Database**: H2 (In-memory 실행)
- **External Keys**: Firebase Admin SDK 비공개 키 JSON 파일 (`yak-allim-firebase-key.json`)

### Configuration

1. Firebase Console에서 발급받은 서비스 계정 키 파일의 이름을 `yak-allim-firebase-key.json`으로 변경하여 백엔드 프로젝트 루트 디렉터리에 배치합니다.
2. `src/main/resources/application.properties` 파일에서 사용할 OCR 엔진 타입을 지정합니다. `ocr.type=local`은 서버가 ONNX Runtime으로 직접 추론하며 아래 3번의 모델 파일이 필요하고, `ocr.type=n8n`은 이미지를 n8n Webhook으로 전달해 n8n 워크플로우가 분석 후 콜백(`POST /api/v1/ocr/n8n/callback/{jobId}`)하는 방식으로 모델 파일은 필요 없는 대신 `OCR_N8N_WEBHOOK_SECRET` 환경 변수가 필요합니다:

   ```properties
   # OCR 엔진 타입 선택: local (로컬 ONNX 엔진) 또는 n8n (n8n Webhook 연동)
   ocr.type=n8n
   ocr.n8n.webhook-url=http://localhost:5678/webhook-test/ocr
   ```

   `ocr.type=n8n`일 때는 `OCR_N8N_WEBHOOK_SECRET` 환경 변수가 반드시 필요합니다(비어 있으면 기동에 실패합니다).
   이 값은 서버 → n8n 요청에는 `X-N8N-WEBHOOK-SECRET` 헤더로, n8n → 서버 콜백에는 `X-N8N-Secret` 헤더로
   전달됩니다(양방향 헤더 이름이 다르니 n8n 워크플로우 설정 시 유의하세요).

3. `ocr.type=local` 모드를 사용할 경우 `src/main/resources/models/` 경로에 아래 모델 파일과 사전이 존재하는지 확인합니다. `*.onnx` 파일은 용량 문제로 `.gitignore`에 포함되어 있어 저장소에 없으므로, [PaddleOCR PP-OCRv4](https://github.com/PaddlePaddle/PaddleOCR) 등에서 별도로 받아 ONNX로 변환하거나 보유한 모델 파일을 직접 배치해야 합니다.
   - `ch_PP-OCRv4_det_infer.onnx` (텍스트 영역 검출 모델)
   - `korean_PP-OCRv4_rec_infer.onnx` (텍스트 인식 모델)
   - `korean_dict.txt` (텍스트 인식용 단어 사전, 저장소에 포함되어 있음)
4. `src/main/resources/data/` 경로에 의약품 사전 데이터가 존재하는지 확인합니다.
   - `medicines.csv`: 현재 6건의 예시 데이터만 포함된 테스트용 사전입니다(공식 의약품 데이터 출처 아님). 실제 서비스에 쓰려면 [식품의약품안전처 의약품안전나라](https://nedrug.mfds.go.kr) 등 공신력 있는 출처의 데이터로 교체하고 출처·라이선스를 이 문서에 명시해야 합니다.

### Installation & Build

1. 저장소를 복제합니다:

   ```bash
   git clone https://github.com/koolunkle-yak-allim/yak-allim-server.git
   ```

2. 백엔드 프로젝트 루트에 `yak-allim-firebase-key.json` 파일을 추가합니다.
3. `ocr.type=local` 환경일 경우 `src/main/resources/models/` 디렉터리에 ONNX 모델 및 사전 파일(`ch_PP-OCRv4_det_infer.onnx`, `korean_PP-OCRv4_rec_infer.onnx`, `korean_dict.txt`)을 추가합니다.
4. 프로젝트를 빌드하고 실행합니다:

   ```bash
   ./gradlew bootRun
   ```

---

## Known Limitations

현재 알려진 제약사항입니다. 각 항목은 순차적으로 개선할 계획입니다.

- **의약품 사전 규모**: `medicines.csv`가 6건뿐인 테스트용 데이터라, 사전에 없는 약품명이 엉뚱한 이름으로 교정될 수 있습니다.
- **작업 상태 영속성 없음**: OCR 작업 상태가 메모리(`ConcurrentHashMap`)에만 저장되어 서버 재시작 시 모두 사라집니다. n8n 콜백이 오지 않아도 처리 시간 초과(`ocr.job-processing-timeout-minutes`, 기본 15분) 시 자동으로 `FAILED` 처리됩니다.
- **파서의 좌표 기반 파싱**: 처방전 이미지 파서가 픽셀 좌표 대신 이미지 가로 크기에 대한 비율로 좌표 설정값을 환산해 쓰지만(S6), 이 비율 자체는 실제 샘플 이미지가 아닌 추정치라 재검증이 필요합니다.
- **n8n 모드 진행률은 개략적인 표시**: n8n 모드는 실제 OCR 단계별 진행 상황을 서버가 알 수 없어, `IMAGE_PROCESSING`/`TEXT_DETECTION`/`TEXT_RECOGNITION` 단계는 실제 진행 단계가 아니라 n8n으로 요청을 전송하기 전후에 발행되는 표시용 값입니다.
- **비동기 실행기 큐 한도**: OCR 비동기 처리는 전용 스레드풀(`ocrTaskExecutor`, 동시 실행 2개·대기열 20개)을 쓰며, 큐가 가득 차면 업로드 파일을 정리하고 503으로 응답합니다. 다만 큐 크기(20)는 실측 없이 정한 추정치라, 실제 트래픽 패턴에 맞춰 재조정이 필요합니다.
