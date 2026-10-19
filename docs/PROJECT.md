# 약올림(Yak-Allim)

> **상태 (2026-10-19 기준)**
>
> - **1차 점검 (2026-09-21 완료)**: 아래 1~4장의 27개 항목과 0장 비밀 값 점검을 모두 반영했습니다.
> - **2차 변경 (2026-10-15 ~ 10-19)**: 서버의 로컬 ONNX OCR과 의약품 사전 교정을 걷어내고 n8n 단일 경로로 바꿨습니다. 그래서 1차 항목 일부는 더 이상 코드에 없습니다. 지금 구조는 [현재 구조](#현재-구조-2026-10-19), 이후 바뀐 내용은 [2차 변경 기록](#2차-변경-기록-2026-10-15--10-19)에 정리했습니다.
> - 1~4장 본문은 당시 "문제 발견 → 해결" 과정의 원본 기록이라 그대로 두었습니다. 대신 항목마다 **현재** 줄을 붙여 지금도 유효한지 표시했습니다.
>
> | 표시 | 뜻 |
> | --- | --- |
> | 유지 | 1차에서 고친 내용이 지금도 그대로 동작 |
> | 변경 | 목적은 유지되지만 구현이나 범위가 바뀜 |
> | 제거 | 관련 코드가 2차 변경으로 없어져 더 이상 해당 없음 |

---

## 현재 구조 (2026-10-19)

```text
[Android 앱] ──업로드──► [Nginx :80] ──► [Spring Boot 서버 (블루-그린)]
     ▲  ▲                                   │  이미지 + jobId
     │  │ SSE 진행률 / 결과 조회               ▼
     │  └──────────────────────────── [n8n 워크플로우 :5678, 로컬 전용]
     │                                       │  Upstage OCR → Gemini(구조화 추출)
     │ FCM 완료·실패 알림                      ▼
     └──────────────── 서버 ◄── 콜백 POST /api/v1/ocr/n8n/callback/{jobId}
```

| 저장소 | 지금 하는 일 |
| --- | --- |
| server | 이미지를 받아 n8n에 넘기고, 콜백으로 받은 결과를 작업 상태·SSE·FCM으로 전달합니다. 약품명은 교정하지 않고 그대로 전달하며, 이름과 1회 양·횟수·일수가 모두 같은 항목만 하나로 합칩니다. 작업 상태는 메모리에만 있습니다. |
| android | 사진 촬영·선택 → 분석 진행 표시 → 약 카드 → 알람 설정 시트. 사진에 없는 값은 `?`와 "확인 필요"로 보여 주고, 사용자가 직접 정해야 알람을 켤 수 있습니다. 디자인 토큰과 화면 구성은 `DESIGN.md`에 있습니다. |
| infra | Nginx(블루-그린 전환), Jenkins(서버 배포·APK 빌드), n8n(OCR 워크플로우)을 compose로 띄웁니다. n8n 워크플로우 정의는 `n8n/workflows/ocr.json`으로 관리합니다. |

**진행 단계 계약** (`docs/api-contract.md`)

| 서버 `PipelineStep` | 앱 `JobStatus` |
| --- | --- |
| ACCEPTED | ENQUEUED |
| ANALYZING | ANALYZING |
| COMPLETED | COMPLETED |
| FAILED | FAILED |
| CANCELLED | CANCELLED |
| (모르는 값) | UNKNOWN (이전 상태 유지) |

**알려진 한계** (각 저장소 README의 Known Limitations와 같은 내용)

- **작업 상태 영속성 없음**: 서버 재시작이나 블루-그린 전환 시 진행 중 작업이 사라집니다. 콜백이 오지 않으면 15분 뒤 `FAILED`로 처리합니다.
- **약품명 검증 없음**: LLM이 읽은 이름을 그대로 씁니다. 앱이 원본 사진과 함께 확인을 안내합니다.
- **외부 서비스 의존**: 처방전 이미지가 Upstage OCR과 Gemini로 전송됩니다. 온도를 0으로 고정했지만 출력이 완전히 같지는 않습니다(같은 사진에서 약 이름 끝 "서방정"이 한 번 빠진 사례가 있었고, 1회 양·횟수·일수는 매번 같았습니다).
- **세부 진행률 없음**: 분석 중에는 `ANALYZING` 하나로만 표시합니다.
- **Jenkins 권한**: Jenkins 컨테이너가 `root`로 `docker.sock`에 접근합니다. 로컬 시연용 구성입니다.

---

## 2차 변경 기록 (2026-10-15 ~ 10-19)

1차 이후 바뀐 내용을 저장소별로 모았습니다. 괄호 안은 각 저장소의 PR 번호입니다.

### 서버

| 변경 | PR |
| --- | --- |
| 로컬 ONNX OCR(PP-OCRv4) 경로 제거, n8n 단일 경로로 전환. 이전 구현은 `archive/onnx-local-ocr` 브랜치에 보존 | #26 |
| API 계약 정리(진행 단계를 ACCEPTED·ANALYZING·종료 단계로 축소), 의약품 사전 교정과 JPA/H2 제거 | #27 |
| 잘못된 요청에 500 대신 원인에 맞는 4xx 응답, 업로드 확장자를 jpg·png로 제한 | #30, #35 |
| 쓰이지 않는 예외·클래스 제거, 이름 정리, n8n 전송·알림 책임 분리, Ktor를 RestClient로 교체 | #31, #32, #33 |
| CI·배포 설정 정리, 컨테이너 비root(`USER app`) 실행 | #34 |
| n8n 기본 webhook 주소를 운영(Publish) 주소로 변경 | #36 |
| n8n 전송 중 취소된 작업에 `ANALYZING`이 발행되지 않도록 수정 | #39 |
| README와 API 계약 문서를 현재 코드에 맞게 정리 | #40 |
| 약품명·1회 양·횟수·일수가 모두 같은 항목을 하나로 합침(영수증 목록의 반복 줄 대응) | #41 |

### 안드로이드

| 변경 | PR |
| --- | --- |
| 서버 계약 변경 반영(n8n 단일 경로, 사전 교정 표시 제거) | #18 |
| 결과 화면에 "이하 n정"처럼 생략된 약 안내 추가 | #21 |
| 디버그 평문 HTTP 허용 호스트를 `BASE_URL`에서 생성, 패키지명을 `io.github.koolunkle.yakallim`으로 변경 | #23, #24 |
| `OcrViewModel`이 Intent 대신 jobId를 받도록, UseCase·Repository 이름을 Analysis로 통일, 쓰지 않는 Moshi 설정 삭제 | #25, #26, #37 |
| 빌드 도구 갱신(AGP 9.4.1, Kotlin 2.4.21, Gradle 9.8.1, Compose BOM 2026.09.00) | #29 ~ #34 |
| 복용 기간이 끝난 알람이 계속 "켜짐"으로 보이던 문제 수정 | #39 |
| 디자인 토큰·테마 도입(Pretendard, 청록 `#0A6F73`, 다크 모드)과 `DESIGN.md` | #40 |
| 약 카드를 보기 전용으로, 알람 설정을 바텀시트로(스테퍼, 시각 칩, 알람음 미리 듣기, 끄기 실행 취소) | #41 |
| 홈·분석 중·결과·오류·카메라 화면을 `DESIGN.md` 구성에 맞춤(결과 요약, 원본 사진 접기, 알람이 있을 때 초기화 확인) | #43 |
| 분석 취소를 오류 카드 대신 스낵바 안내로, 상단바 앱 이름을 왼쪽에 작게 | #44, #45 |

### 인프라

| 변경 | PR |
| --- | --- |
| n8n 워크플로우에 실패 콜백·결과 검증 추가, 성공 콜백 status를 `COMPLETED`로 | #9, #10, #11 |
| LLM 호출에 재시도 5회와 대체 모델 적용, 주 모델을 Gemini 3.5 Flash-Lite로 | #12, #13, #14 |
| ngrok Traffic Policy로 Jenkins 배포 webhook만 외부에 공개 | #15 |
| 쓰이지 않는 설정 정리, nginx 헬스체크 IPv4 수정, n8n 실행 기록 보관 24시간 | #16, #17, #19 |
| 약품 추출 범위를 복약안내 영역으로 제한했다가 되돌림(두 목록 모두 추출하기로 결정) | #20, #21 |
| 문서에 n8n 게시(Publish) 규칙과 Android 배포 흐름 반영 | #22 |
| 사진에 없는 1회 양·횟수·일수를 LLM이 지어내지 않고 `null`로 두도록 프롬프트·스키마 수정 | #23 |
| Gemini 두 모델의 온도를 0으로 고정 | #24 |

---

> **읽기 전에 (1차 점검 당시)**
>
> - 코드는 zip으로 정독했고, **Gradle 빌드·실행은 하지 못했습니다.** 아래 코드 조각은 적용 후 `./gradlew test`로 반드시 확인하세요.
> - 약품명 매칭 규칙(S1)의 수치는 저장소의 알고리즘을 파이썬으로 재현해 **6건짜리 샘플 사전** 기준으로만 검증했습니다. 사전이 커지면 다시 조정해야 합니다.
> - zip에는 `.git` 기록이 없어서 **과거 커밋에 남은 비밀 값은 확인하지 못했습니다.** (0장 참고)

---

## 0. 작업 원칙

**브랜치와 PR**

- 항목(S1, A2 등)마다 브랜치 하나, PR 하나로 나누세요. 예: `fix/medicine-matching-threshold`
- PR 본문에 **문제 → 원인(근거) → 수정 → 검증(테스트 또는 측정)**을 남기세요. 그대로 포트폴리오의 "문제 해결" 소재가 됩니다.
- 이미 공개된 저장소이므로 history를 다시 쓰지(force-push) 마세요. 비밀 값이 발견된 경우에만 예외입니다.

**2차 변경부터 지키는 규칙** (현재)

- **커밋 메시지**: conventional 제목, 1~3줄 이유, 그 아래 바뀐 점을 글머리표로. 검증 내용은 커밋이 아니라 PR 본문에 적습니다.
- **머지**: PR은 squash로 머지합니다. 앞 PR에 쌓은(stacked) PR은 앞 PR이 squash 머지된 뒤 base가 main으로 바뀌지 않아 옛 브랜치로 머지될 수 있습니다. 이어지는 PR은 main에서 새로 만들거나, 앞 PR 머지 직후 base를 main으로 바꾸세요.
- **브랜치 정리**: PR이 MERGED인 브랜치만 지웁니다. main과 내용이 같은지(`git diff --quiet origin/main <branch>`) 확인한 뒤 원격과 로컬을 지우고, 이후 main CI와 Jenkins 결과를 확인합니다.
- **n8n 변경**: 워크플로우는 저장(Save)만 하면 실행 버전이 바뀌지 않습니다. 반드시 게시(Publish)하고, 실제 실행 버전에 반영됐는지 확인합니다.
- **개인정보**: 처방전 사진과 그 사진이 보이는 화면 캡처는 저장소·PR·문서에 올리지 않습니다. 실기기 확인에 쓴 시험 사진과 캡처는 확인 후 지웁니다.

**시작 전 한 번: 과거 커밋의 비밀 값 점검** (로컬 `.git`이 있으니 가능합니다)

```bash
# gitleaks가 있다면
gitleaks detect --source . --log-opts="--all"

# 없다면 간단 점검
git log --all -p | grep -iE "BEGIN (RSA |EC )?PRIVATE KEY|AIza[0-9A-Za-z_-]{20,}|ghp_[0-9A-Za-z]{20,}|password\s*=|secret\s*=" | head
```

키 파일을 올렸다 지운 흔적이 있으면 그 키는 **폐기하고 재발급**하세요. 삭제된 커밋에도 값이 남아 있습니다.

**권장 진행 순서**

| 단계 | 항목 | 이유 |
| --- | --- | --- |
| 1. 빠른 정리 | S3, S4, I1, I2, 문서 정정(S9, A7) | 공개 저장소의 안전·정확성 문제이고 수정이 작습니다 |
| 2. 핵심 품질 | S1, X1, X2, A1, S5, S7 | 정확도와 서버-앱 계약이 걸린 핵심입니다 |
| 3. 기능 완성 | A2, A3, S2 | 복약 알림 앱으로서의 신뢰성입니다 |
| 4. 측정 가능한 개선 | A4, S6, I6 | 전후를 측정해서 실제 성과로 남길 수 있습니다 |

**포트폴리오 표기 규칙:** 끝낸 항목만 "개선"으로, 남은 항목은 "개선 계획"으로 쓰세요. 측정하지 않은 수치는 쓰지 마세요.

---

## 1. 서버 (yak-allim-server)

### S1. 약품명 오교정 (최우선)

> **현재** **제거** — 의약품 사전 교정 기능을 2차에서 삭제했습니다(server #27). 서버는 LLM이 읽은 이름을 앞뒤 공백만 정리해 그대로 전달하고, 앱이 원본 사진과 함께 확인을 안내합니다.

**문제** `MedicineService.findStandardName`이 편집 거리 ≤ 8이면 무조건 가장 가까운 사전 항목으로 바꿉니다(`MedicineProperties.similarityThreshold` 기본값 8, `application.properties`에서 재정의 없음). 사전(`medicines.csv`)이 6건이라, 사전에 없는 약이 엉뚱한 이름으로 바뀝니다.

| 입력 | 현재 결과(거리) |
| --- | --- |
| 타이레놀정 | 아르레온정 (5) |
| 아스피린정 | 아르레온정 (5) |
| 게보린정 | 메디솔론정 (6) |
| 훼스탈정 | 베포스타비정 (6) |

**수정** 절대값 임계값을 **상대 비율 + 부분수열 포함 + 1·2위 간격** 규칙으로 바꿉니다. 재현 결과 아래 규칙이 기존 테스트 3건(`야르레온정`, `모튼슐`, `비드레바서방정`)을 모두 통과시키고 위 4건을 모두 원문 유지로 돌립니다.

```kotlin
// HangulUtils.kt
fun isSubsequence(query: String, target: String): Boolean {
    var i = 0
    for (ch in target) if (i < query.length && query[i] == ch) i++
    return i == query.length
}
```

```kotlin
// MedicineProperties.kt
@ConfigurationProperties(prefix = "medicine.standard")
data class MedicineProperties(
    val dataPath: String = "data/medicines.csv",
    val maxDistanceRatio: Double = 0.25,        // 편집거리 / 긴 쪽 자모 길이
    val minSubsequenceCoverage: Double = 0.6,   // 글자 누락(부분수열)일 때 최소 포함 비율
    val minMargin: Int = 2                      // 1위와 2위의 거리 차이
)
```

```kotlin
// MedicineService.findStandardName 의 후보 비교 부분
private data class Scored(val name: String, val normalized: String, val distance: Int)

val scored = candidates.map {
    Scored(it.name, it.normalizedName, HangulUtils.levenshteinDistanceTo(normalizedName, it.normalizedName))
}.sortedBy { it.distance }

val best = scored.first()
val second = scored.getOrNull(1)
val ratio = best.distance.toDouble() / maxOf(normalizedName.length, best.normalized.length)
val coverage = normalizedName.length.toDouble() / best.normalized.length

val closeEnough = ratio <= medicineProperties.maxDistanceRatio ||
    (HangulUtils.isSubsequence(normalizedName, best.normalized) &&
        coverage >= medicineProperties.minSubsequenceCoverage)
val clearWinner = second == null || second.distance - best.distance >= medicineProperties.minMargin

return if (closeEnough && clearWinner) best.name else rawName
```

**함께 할 것**

- 사전에 없는 약을 원문으로 유지하도록 하는 대신, 응답에 **`rawName`(OCR 원문)과 `autoCorrected`(교정 여부)**를 넣으세요. (X2 참고)
- 사전을 실제 의약품 데이터로 확장할 때는 출처와 라이선스를 확인하고, README에 출처를 적으세요.
- 사전이 수천 건 이상이 되면 `findAll()` 폴백이 느려집니다. 그때 인덱스(예: BK-tree)를 고려하세요.
- 샘플 6건에서 정한 수치(0.25, 0.6, 2)는 **사전 확장 후 라벨링한 소규모 평가 세트**로 다시 검증하세요.

**검증** `MedicineServiceTest`에 추가:

```kotlin
@Test
fun `사전에 없는 약품명은 원문을 유지한다`() {
    listOf("타이레놀정", "아스피린정", "게보린정", "훼스탈정").forEach {
        Assertions.assertEquals(it, medicineService.findStandardName(it))
    }
}
```

기존 3개 테스트는 그대로 통과해야 합니다.

### S2. 작업 상태 만료·타임아웃

> **현재** **유지** — `OcrJobCleanupScheduler`가 1분마다 종료 작업을 60분 뒤 삭제하고, 15분 안에 콜백이 없으면 `FAILED`(`OCR_PROCESSING_TIMEOUT`)로 바꿉니다. 영속화(JPA)는 하지 않았고 H2도 2차에서 제거했습니다.

**문제**

- `InMemoryOcrJobRepository`(`ConcurrentHashMap`)에 작업이 쌓이기만 합니다(H2가 아닙니다). 재시작하면 모두 사라집니다.
- n8n 모드에서 n8n이 콜백을 보내지 않으면 작업이 `PROCESSING`으로 영원히 남고, `N8nOcrService.fcmTokenMap`의 토큰도 남습니다.
- `OcrProgressManager.progressCache`도 종료 이벤트 없이 끝나면 남을 수 있습니다.

**수정**

1. 저장소에 `updatedAt`을 함께 기록합니다(API 응답 DTO는 바꾸지 말고 내부 래퍼로).
2. `@EnableScheduling` + `@Scheduled(fixedDelay = 60_000)`로 정리합니다.
   - 종료 상태(COMPLETED, FAILED, CANCELLED)가 N분 지난 작업 삭제
   - `PROCESSING`이 M분을 넘으면 `FAILED`("처리 시간 초과")로 전이하고 FCM으로 알림, 토큰 맵과 진행률 캐시도 정리
3. (선택) 영속화가 필요하면 JPA로 옮기세요. 이 경우 infra의 compose에 MariaDB를 추가하면 **MariaDB를 실제 프로젝트에서 쓰게 되는 경험**이 됩니다.

**검증** 시간을 주입할 수 있게 `Clock`을 받도록 만들고, "만료 후 삭제", "타임아웃 후 FAILED 전이"를 단위 테스트로 확인합니다.

### S3. 처방전 이미지 삭제

> **현재** **변경** — 로컬 파이프라인이 없어져 `OcrJobDispatcher`가 n8n 전송 뒤, `OcrService`가 대기열이 가득 차 거절될 때 파일을 지웁니다. 안전망 `UploadCleanupScheduler`가 60분 지난 업로드를 30분마다 지웁니다.

**문제** `outputs/api-images`에 저장한 이미지를 **어디에서도 지우지 않습니다.** 처방전은 개인정보라 보관 정책이 필요합니다.

**수정**

```kotlin
// OcrJobProcessor.executeTask: 경로 검증 뒤 전체를 try/finally로
try {
    // ... 기존 처리 ...
} finally {
    runCatching { Files.deleteIfExists(path) }
}
```

- n8n 모드는 `N8nOcrClient.sendToN8nAsync`의 `finally`에서 `file.delete()` (전송 성공·실패 모두).
- 안전망: `@Scheduled`로 `ocr.retention-minutes` 이상 지난 잔여 파일을 삭제합니다.

**검증** 완료, 실패, 취소 각각 뒤에 파일이 없는지 테스트합니다.

### S4. 운영 보안 설정

> **현재** **유지** — 시크릿 필수·상수 시간 비교·401·토큰 앞 6자 로그가 그대로입니다. H2 콘솔 행은 H2 자체를 제거해 해당 없습니다. 양방향 헤더 이름은 통일하지 않고 README와 API 계약 문서에 적어 두었습니다.

**문제와 수정**

| 문제 | 수정 |
| --- | --- |
| `application.properties`의 `spring.h2.console.enabled=true` | 메인은 `false`, `application-dev.properties`에서만 `true` |
| 웹훅 시크릿 기본값 `change-me-in-production` (환경 변수가 없으면 추측 가능한 값이 그대로 사용됨) | 기본값 제거, n8n 모드 기동 시 비어 있으면 실패하게 |
| 시크릿 비교가 `!=` | `MessageDigest.isEqual`로 상수 시간 비교 |
| 시크릿 불일치가 `IllegalJobStateException`(400) | 전용 예외로 **401** |
| 서버→n8n 헤더 `X-N8N-WEBHOOK-SECRET`, n8n→서버 헤더 `X-N8N-Secret` (이름이 다름) | 하나로 통일하거나 README에 명시 |
| FCM 실패 로그에 토큰 전체 출력 | 앞 6자만 출력 |

```yaml
# application.properties
ocr.n8n.webhook-secret=${OCR_N8N_WEBHOOK_SECRET:}
```

```kotlin
@Component
@ConditionalOnProperty(name = ["ocr.type"], havingValue = "n8n")
class N8nSecretValidator(private val props: OcrProperties) {
    @PostConstruct
    fun validate() = require(props.n8n.webhookSecret.isNotBlank()) {
        "ocr.type=n8n 에서는 OCR_N8N_WEBHOOK_SECRET 이 필요합니다."
    }
}
```

```kotlin
// OcrException.kt (sealed class 안에 추가)
class UnauthorizedWebhookException(message: String = "유효하지 않은 webhook 요청입니다.") :
    OcrException(HttpStatus.UNAUTHORIZED, message)
```

**검증** `OcrControllerTest`에 "시크릿 없음/틀림 → 401", "일치 → 200" 추가.

### S5. 오류 처리

> **현재** **제거** — `OcrJobProcessor`와 `OnnxOcrEngine`이 로컬 경로와 함께 없어졌습니다. n8n에 전송하는 동안 취소되거나 콜백이 먼저 도착한 작업에는 `ANALYZING`을 보내지 않습니다(server #39).

**문제**

- `OcrJobProcessor`가 취소를 `check(...)`(`IllegalStateException`)로 표현하고 `catch (e: IllegalStateException)`으로 잡습니다. 라이브러리에서 발생한 다른 `IllegalStateException`도 "취소"로 처리되어, 작업이 `FAILED`가 아닌 채로 남을 수 있습니다.
- `OnnxOcrEngine.runOcr`가 모델이 로드되지 않으면(`!isReady`) **빈 목록을 반환**해서 "성공했는데 결과 없음"으로 보입니다.

**수정**

```kotlin
class JobCancelledException(message: String) : RuntimeException(message)

private fun ensureNotCancelled(jobId: String, stage: String) {
    if (ocrJobRepository.isCancelled(jobId)) throw JobCancelledException("$stage 전 취소됨")
}
// catch (e: JobCancelledException) { ... 취소 처리 }
```

```kotlin
// OcrException.kt
class EngineNotReadyException(message: String = "OCR 엔진이 준비되지 않았습니다.") :
    OcrException(HttpStatus.SERVICE_UNAVAILABLE, message)

// OnnxOcrEngine.runOcr
if (!isReady) throw OcrException.EngineNotReadyException()
```

**검증** 취소 시나리오 테스트가 그대로 통과해야 하고, 엔진 미준비 시 작업이 `FAILED`가 되는 테스트를 추가합니다. (`test` 프로파일은 `MockOcrEngine`을 쓰니 별도 테스트로)

### S6. 파서의 픽셀 좌표 의존 (규모 중간)

> **현재** **제거** — 좌표 기반 파서가 로컬 경로와 함께 없어졌습니다. 표 해석은 n8n의 LLM이 맡습니다.

**문제** `medicine-min-x=130`, `medicine-max-x=260`, `column-separator-x=600`, `y-deviation-threshold=75` 같은 값이 특정 문서와 해상도에 맞춰져 있습니다. 앱이 긴 변을 1920px로 줄여 보내지만, 작은 이미지는 확대하지 않아서 좌표 스케일이 입력마다 달라집니다.

**수정**

1. `OcrEngine`이 텍스트 블록과 함께 **원본 이미지의 가로·세로**를 돌려주게 합니다.
2. 파서에서 좌표를 0~1 비율로 정규화한 뒤 비율 기준 설정(`*-ratio`)을 씁니다. 기존 픽셀 값은 샘플 이미지 폭으로 나눠 초기값을 만드세요.
3. 개인정보가 없는 **서로 다른 해상도의 샘플 이미지** 몇 장으로 확인합니다.

**검증** 같은 이미지를 두 해상도로 만들어 같은 파싱 결과가 나오는지 테스트합니다.

### S7. 테스트 보강

> **현재** **변경** — 지금 서버 테스트: `ApiContractTest`, `OcrControllerTest`, `OcrControllerErrorResponseTest`, `OcrWebhookSecretTest`, `OcrServiceTest`, `OcrServiceRejectionTest`, `OcrJobDispatcherTest`, `OcrJobCleanupSchedulerTest`, `InMemoryOcrJobRepositoryTest`, `N8nOcrClientTest`, `OcrPropertiesValidationTest`, `GlobalExceptionHandlerTest`.

| 대상 | 내용 |
| --- | --- |
| `MedicineService` | S1의 오교정 방지 케이스 |
| `PrescriptionParser` | ONNX 없이 **합성 `TextBlock`**(좌표 포함)으로 단위 테스트: 복용량·횟수·일수 추출, `11 → 1`, 기울기 보정, 헤더 탐지, 이름·복용 행 매칭 |
| `InMemoryOcrJobRepository` | 취소 우선, 완료 전이 조건, 만료 |
| 컨트롤러 | 웹훅 시크릿 401/200, 확장자와 빈 파일 |
| 실제 이미지 테스트 | `if (!exists) return` 대신 `Assumptions.assumeTrue(imageFile.exists())`로 **건너뛰었음을 리포트에 표시** |

파이프라인 테스트는 `assertNotNull`만 확인하지 말고, 합성 입력의 **기대 값**을 비교하세요.

### S8. n8n 모드와 로컬 모드 일관성

> **현재** **제거** — 경로가 n8n 하나라 두 모드의 차이가 없어졌습니다. 진행률은 `ACCEPTED` → `ANALYZING` → 종료 단계뿐이고, README의 Known Limitations에 "세부 진행률 없음"으로 적었습니다.

**문제** 자모 정규화는 로컬 파이프라인의 `PrescriptionParser`에서만 실행됩니다. n8n 콜백 결과는 정규화 없이 저장됩니다. n8n 모드의 진행률(IMAGE_PROCESSING, TEXT_DETECTION, TEXT_RECOGNITION)도 실제 단계가 아니라 전송 전후에 발행되는 표시용입니다.

**수정**

- `N8nOcrService.handleCallback`에서 `medicineService.findStandardName`을 적용하거나, n8n 워크플로우가 정규화한다면 README에 명시
- README에 "n8n 모드의 진행률은 개략적인 표시" 명기
- 비동기 실행기 정리(선택, S10)

### S9. 문서와 저장소 정리

> **현재** **유지** — README와 API 계약 문서를 2차에서 현재 코드에 맞게 다시 정리했습니다(server #40).

- README의 `git clone https://github.com/your-username/...` 자리표시자를 실제 주소로
- README에 `ocr.type` 동작 차이, 필요한 환경 변수, 모델 배치 위치(저장소에 `*.onnx`가 없음), 의약품 사전 규모와 출처 명시
- GitHub About(설명·토픽) 작성
- Swagger에 `PipelineStep`을 enum 스키마로 노출 (X1)
- 저장소의 "알려진 한계" 절에 남은 항목 기록

### S10. 비동기 실행기 (선택)

> **현재** **유지** — `ocrTaskExecutor`(동시 2개·대기열 20개)는 n8n 전송에 쓰고, 큐가 가득 차면 업로드를 지우고 503으로 응답합니다. `server.shutdown=graceful`과 60초 대기도 그대로입니다.

`@EnableAsync`만 있고 실행기 설정이 없어서 Spring Boot 기본 실행기를 씁니다. OCR은 CPU 집약이라 **동시 실행 수와 대기열을 제한**하는 편이 안전합니다.

```kotlin
@Configuration
class AsyncConfig {
    @Bean("ocrTaskExecutor")
    fun ocrTaskExecutor() = ThreadPoolTaskExecutor().apply {
        corePoolSize = 2; maxPoolSize = 2; queueCapacity = 20
        setThreadNamePrefix("ocr-")
        setRejectedExecutionHandler(ThreadPoolExecutor.AbortPolicy())
        setWaitForTasksToCompleteOnShutdown(true)
        setAwaitTerminationSeconds(60)
    }
}
// @Async("ocrTaskExecutor")
```

- 큐가 가득 차면 거절 예외가 나므로, **`enqueueJob`에서 잡아서** 업로드 파일을 지우고 503을 반환하세요. (안 그러면 S3의 이미지 잔류가 다시 생깁니다.)
- `server.shutdown=graceful`, `spring.lifecycle.timeout-per-shutdown-phase=60s`도 함께 설정하세요. (I6과 연결)

---

## 2. 안드로이드 (yak-allim-android)

### A1. 서버-앱 진행 상태 계약 불일치

> **현재** **변경** — 서버 단계가 줄어 앱 `JobStatus`는 `ENQUEUED`, `ANALYZING`, `COMPLETED`, `FAILED`, `CANCELLED`, `UNKNOWN`입니다. `UNKNOWN`이면 이전 상태를 유지하는 규칙은 그대로이고, `OcrMappersTest`가 목록 일치를 확인합니다.

**문제**

| 서버 `PipelineStep` | 앱 `JobStatus` |
| --- | --- |
| ACCEPTED | ENQUEUED |
| IMAGE_PROCESSING | IMAGE_PROCESSING |
| TEXT_DETECTION | TEXT_DETECTION |
| TEXT_RECOGNITION | TEXT_RECOGNITION |
| **PARSING** | (없음) |
| (없음) | **EXPORT_RESULT** (어디에서도 쓰이지 않음) |
| COMPLETED | COMPLETED |
| FAILED | FAILED |

`OcrRemoteDataSourceImpl`이 `JobStatus.valueOf(step)`을 쓰고 실패하면 `FAILED`로 바꿉니다. 그래서 **ACCEPTED와 PARSING 단계가 내부적으로 FAILED로 저장됩니다.** 종료 판단은 서버의 `isFinished`를 써서 화면이 바로 깨지지는 않지만, 계약이 어긋나 있습니다.

**수정** 매퍼를 만들어 명시적으로 변환하고, 모르는 단계는 `FAILED`가 아니라 `UNKNOWN`으로 두세요.

```kotlin
// domain/model/JobStatus.kt
enum class JobStatus {
    ENQUEUED, IMAGE_PROCESSING, TEXT_DETECTION, TEXT_RECOGNITION, PARSING, COMPLETED, FAILED, UNKNOWN;
    val isFinished: Boolean get() = this == COMPLETED || this == FAILED
}
```

```kotlin
// data/mapper/OcrMappers.kt
fun OcrProgressResponse.toDomain(): Progress {
    val status = when (step) {
        "ACCEPTED" -> JobStatus.ENQUEUED
        "IMAGE_PROCESSING" -> JobStatus.IMAGE_PROCESSING
        "TEXT_DETECTION" -> JobStatus.TEXT_DETECTION
        "TEXT_RECOGNITION" -> JobStatus.TEXT_RECOGNITION
        "PARSING" -> JobStatus.PARSING
        "COMPLETED" -> JobStatus.COMPLETED
        "FAILED" -> JobStatus.FAILED
        else -> JobStatus.UNKNOWN
    }
    return Progress(status, message ?: "", progress ?: 0, isFinished)
}
```

- ViewModel은 `UNKNOWN`이면 **이전 상태를 유지**하세요.
- `OcrRemoteDataSourceImpl`의 `catch (_: Exception) {}`는 최소한 `Log.w`로 남기세요.

**검증** `OcrMappersTest`에 서버 단계 이름 7개가 모두 `UNKNOWN`이 아닌 값으로 변환되는 테스트를 추가합니다. (X1의 계약 문서와 같은 목록을 쓰세요.)

### A2. 재부팅 후 알람 소실

> **현재** **유지** — `BootReceiver`와 `AlarmTimeCalculator`가 그대로입니다. 2차에서 복용 기간이 끝난 알람을 목록과 저장소에서 빼도록 고쳤습니다(android #39). 시간대·시각 변경 브로드캐스트(4번)는 처리하지 않습니다. 패키지명이 바뀌어 확인 명령은 `adb shell dumpsys alarm | grep io.github.koolunkle.yakallim`입니다.

**문제** `AlarmManager` 알람은 재부팅하면 사라지는데, `BOOT_COMPLETED` 처리가 없습니다(Manifest에 부팅 권한과 리시버가 없음). 또 `AlarmPreference`에는 이름별 개수와 시각·소리만 있고 **시작 시점, 복용량, 횟수, 기간**이 없어서 재등록이 불가능합니다.

**수정**

1. **알람 스펙 저장:** `AlarmSpec(medicineName, dosagePerTake, dailyFrequency, durationDays, alarmTimes, soundUri, startEpochMillis)`를 JSON으로 저장합니다(kotlinx.serialization).
2. **계산 분리:** 알람 시각 계산을 순수 함수로 뽑습니다.

   ```kotlin
   object AlarmTimeCalculator {
       fun triggerTimes(spec: AlarmSpec, nowMillis: Long, zone: ZoneId): List<Long>
   }
   ```

   `AlarmSchedulerImpl.schedule`도 이 함수를 쓰게 하세요. (지금은 `now` 기준으로 매번 계산)
3. **부팅 리시버:**

   ```xml
   <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
   <receiver android:name=".data.infrastructure.alarm.BootReceiver" android:exported="true">
       <intent-filter>
           <action android:name="android.intent.action.BOOT_COMPLETED" />
       </intent-filter>
   </receiver>
   ```

   `BootReceiver`에서 `goAsync()`로 코루틴을 실행해 저장된 스펙의 **남은 알람**을 다시 등록합니다.
4. (선택) 시간대·시각 변경(`TIMEZONE_CHANGED`, `TIME_SET`)과 정확한 알람 권한 변경 브로드캐스트도 처리합니다.
5. 요청 코드는 `medicineName.hashCode() + index`라 충돌 가능성이 있으니 **안정적인 고유 ID**로 바꾸세요. (A3의 약품 ID와 함께)

**검증**

- `AlarmTimeCalculator` 단위 테스트: 일수 경계, 하루 횟수, 이미 지난 시각 제외, 시간대
- 수동: 알람을 등록한 뒤 `adb reboot`, 부팅 후 `adb shell dumpsys alarm | grep com.example.yakallim`로 남은 알람이 등록됐는지 확인

### A3. ViewModel과 구조 정리

> **현재** **유지** — `AlarmViewModel` 분리, 약품 `id`, 의존 방향이 그대로입니다. 2차에서 `OcrViewModel`이 Intent 대신 jobId를 받도록 바꿨고(android #25), 카드 펼침 상태는 카드 개편으로 없어졌습니다(android #41).

**문제** (`OcrViewModel`)

- 생성자 의존성이 14개로 책임이 너무 많습니다(분석, 알람, 캐시 파일, 복구).
- 데이터 레이어 타입(`data.infrastructure.image.ImageProcessor`)을 직접 import합니다. Clean Architecture의 의존 방향과 어긋납니다.
- `throw CancellationException("Progress finished")`로 `collect`를 벗어납니다.
- 알람 등록 실패를 `catch (_: Exception) {}`로 삼켜서 사용자가 알 수 없습니다.
- 약품 이름을 맵의 키(알람, 카드 펼침)로 씁니다. **이름이 같은 약이 두 개면 충돌**합니다. (`.agent/tasks`의 중복 키 크래시 수정은 `LazyColumn` 키만 고쳤음)

**수정**

1. **분리:** 알람 관련(등록, 취소, 복구)을 `AlarmViewModel`로 나눕니다.
2. **의존 방향:** `ImageProcessor` 인터페이스를 `domain`으로 옮기고, `PrepareImageUseCase`를 거쳐 사용합니다. 캐시 파일 처리는 Repository로 이동합니다.
3. **흐름 제어:** 예외 대신

   ```kotlin
   observeProgressUseCase(jobId)
       .onEach { progress -> updateProgress(progress) }
       .first { it.isFinished }   // 조건에 맞으면 수집을 정상 종료
   ```

4. **오류 표시:** 알람 실패를 `UiState`의 오류 또는 일회성 이벤트(Snackbar)로 노출합니다.
5. **약품 ID:** `PrescribedMedicine`에 안정적인 `id`를 두고 키와 요청 코드에 사용합니다.

**검증** ViewModel 테스트(A8)로 리팩터링 전후 동작이 같음을 확인합니다.

### A4. 이미지 디코딩 메모리 (측정 가능한 개선)

> **현재** **유지** — `ImageProcessorImpl`이 `inSampleSize`로 줄여 읽습니다. `ImageProcessorImplTest`가 있습니다.

**문제** `ImageProcessorImpl.preprocess`가 `BitmapFactory.decodeFile`로 **원본 전체를 디코딩한 뒤** 1920px로 줄입니다. 고해상도 사진(예: 48MP)에서는 메모리 부족 위험이 있습니다. 또 `printStackTrace`, 스트림 미종료(`use` 아님) 문제도 있습니다.

**수정**

```kotlin
val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
BitmapFactory.decodeFile(file.absolutePath, bounds)
val opts = BitmapFactory.Options().apply {
    inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, maxDimension = 1920)
}
var bitmap = BitmapFactory.decodeFile(file.absolutePath, opts)
// 이후 남은 축소(정확한 1920px)와 EXIF 회전, 저장은 기존대로
```

```kotlin
internal fun calculateInSampleSize(width: Int, height: Int, maxDimension: Int): Int {
    var w = width; var h = height; var sample = 1
    while (maxOf(w, h) / 2 >= maxDimension) { w /= 2; h /= 2; sample *= 2 }
    return sample
}
```

- `FileOutputStream`은 `use { }`로, `printStackTrace`는 `Log.e`로 바꾸세요.

**검증 (성과로 남길 부분)**

- 고해상도 샘플 이미지 하나로 **Android Studio Memory Profiler**에서 변경 전후의 **최대 메모리 사용량**을 측정하세요.
- 측정 환경(기기, 이미지 크기)과 전후 값을 PR에 기록하세요. **직접 측정한 값만** 이력서·포트폴리오에 쓰세요.
- `calculateInSampleSize` 단위 테스트를 추가하세요.

### A5. 알 수 없는 값을 기본값으로 채우는 문제 (복약 안전)

> **현재** **변경(강화)** — 서버와 n8n도 사진에 없는 값을 `null`로 보냅니다(infra #23). 앱은 그 자리에 `?`와 "확인 필요"를 보여 주고, 알람 설정 시트에서 "선택"으로 비워 둔 채 사용자가 직접 정해야 "알람 켜기"가 눌립니다(android #41).

**문제** `OcrMappers.toDomain`이 값이 없으면 `dosagePerTake ?: "1"`, `dailyFrequency ?: 0`, `durationDays ?: 0`으로 채웁니다. 복약 앱에서 "인식 실패"가 "1회 복용"처럼 보이면 위험합니다.

**수정**

- 도메인 모델의 세 필드를 `nullable`로 바꾸고, 값이 없으면 카드에 **"확인 필요"**를 표시합니다.
- 알람 등록 다이얼로그는 필수 값이 없으면 **사용자가 직접 입력해야** 진행되게 합니다.

### A6. 신뢰도 표시가 교정 후에는 동작하지 않음

> **현재** **제거** — 서버가 `rawName`·`autoCorrected`·`confidence`를 보내지 않게 되어 표시도 지웠습니다(android #18). 대신 결과 화면 안내 배너에서 사진과 대조하라고 안내합니다.

**문제** `isLowConfidence`를 `textBlocks.find { it.text.contains(medicineName) }`로 계산합니다. 서버가 이름을 **교정한 뒤에는** 원문 텍스트에 그 이름이 없어서 항상 못 찾고, 신뢰도 기본값 1.0으로 "낮음 아님"이 됩니다. n8n 모드는 `textBlocks`가 비어서 마찬가지입니다.

**수정** 서버 응답에 약품별 `confidence`, `rawName`, `autoCorrected`를 넣고(X2), 앱은 이를 그대로 쓰세요. 자동 교정된 약품에는 **"자동 교정됨"** 표시와 원문 확인 기능을 붙입니다.

### A7. 설정·문서

> **현재** **유지** — 백업 제외, 디버그 전용 평문 허용(2차에서 `BASE_URL`로 호스트 생성, android #23)이 그대로이고, Moshi 설정은 완전히 지웠습니다(android #37). 키 파일(`google-services.json`, `secrets.properties`)은 git에서 추적하지 않습니다.

| 항목 | 수정 |
| --- | --- |
| README 기술 스택 "Moshi" | 실제 사용하는 **kotlinx.serialization**으로 정정 (`libs.versions.toml`의 Moshi는 주석 처리됨) |
| `docs`나 README에 Room을 쓴다는 표현 | 삭제 (`dao`, `entity`는 `.gitkeep`뿐). 사용 계획이 없으면 폴더도 정리 |
| `AndroidManifest`의 `allowBackup="true"` | DataStore에 FCM 토큰과 **마지막 처방 결과(JSON)**가 저장됨. `false`로 하거나 백업 제외 규칙 적용 |
| `network_security_config.xml`의 하드코딩된 LAN IP `192.168.45.175` | 저장소에서 제거. cleartext 허용은 `src/debug/res/xml/`의 디버그용 설정으로 분리 |
| README `your-username` 자리표시자 | 실제 주소로 |
| `secrets.defaults.properties` | 더미 값(`BASE_URL=https://api.example.com/`)인지 계속 확인 |

### A8. 테스트

> **현재** **변경** — 지금 앱 테스트: 단위 `OcrViewModelTest`, `AlarmViewModelTest`, `AlarmTimeCalculatorTest`, `AlarmFormDefaultsTest`, `OcrMappersTest`, `ServerContractParsingTest`, `FcmPayloadSpecTest`, `ImageProcessorImplTest` / UI `OcrScreenTest`, `OcrScreenPreviewTest`.

현재는 단위 1개(`OcrMappersTest`)와 UI 2개뿐입니다.

| 대상 | 내용 |
| --- | --- |
| `OcrViewModel` (가짜 UseCase + Turbine + `kotlinx-coroutines-test`) | SSE 성공, SSE 실패 → FCM 대기, 앱 복귀 시 진행 중 작업 복구, 취소, 알람 실패 표시 |
| 매퍼 | A1의 단계 7개, A5의 null 처리 |
| `AlarmTimeCalculator` | A2의 경계 케이스 |
| `calculateInSampleSize` | A4 |

---

## 3. 인프라 (yak-allim-infra)

### I1. Nginx 경로 불일치와 업로드 한도

> **현재** **유지** — SSE 경로(버퍼링 끔, 300초)와 `/api/v1/ocr/`(10M) 설정이 그대로이고, 10MB를 넘으면 nginx가 413을 돌려줍니다.

**문제**

- `location /api/ocr/`(300초 타임아웃)이 있지만 서버의 실제 경로는 `/api/v1/ocr/`입니다. **이 설정은 적용되지 않고** 기본 `location /`(60초)로 처리됩니다. (SSE는 서버의 15초 keep-alive 덕분에 유지됩니다)
- `client_max_body_size 50M`이지만 서버 `spring.servlet.multipart.max-file-size`는 **10MB**입니다. 한도가 다릅니다.

**수정**

```nginx
# SSE 진행률: 버퍼링 끄기, 긴 read timeout
location ~ ^/api/v1/ocr/jobs/[^/]+/progress$ {
    proxy_pass $service_url;
    proxy_http_version 1.1;
    proxy_set_header Connection "";
    proxy_buffering off;
    proxy_cache off;
    proxy_read_timeout 300s;
}

# 그 외 OCR API
location /api/v1/ocr/ {
    proxy_pass $service_url;
    client_max_body_size 10M;      # 서버 한도와 맞춤
    proxy_read_timeout 60s;
}
```

### I2. 노출과 보안

> **현재** **변경** — `/jenkins/`, `/n8n/` 프록시 자체를 없앴고 `/actuator`는 404입니다. Jenkins는 ngrok Traffic Policy로 배포 webhook만 외부에 엽니다(infra #15). Jenkins의 `root`·`docker.sock` 문제는 남아 있습니다(README 보안 주의). 서버 컨테이너는 비root로 돕니다(server #34).

**문제**

- Jenkins가 `root`로 돌고 `docker.sock`을 마운트합니다. 사실상 호스트 root 권한입니다.
- `9090`과 `50000` 포트가 모든 인터페이스에 열려 있습니다.
- Nginx가 `/jenkins/`, `/n8n/`를 외부에 프록시합니다. n8n 포트를 `127.0.0.1`에 묶은 의도가 Nginx 프록시 때문에 약해집니다.
- `443:443`을 열었지만 TLS 설정이 없습니다.
- 서버의 `/actuator`, `/h2-console`, `/swagger-ui`가 Nginx를 통해 접근 가능합니다.

**수정**

```yaml
# docker-compose.yml
jenkins:
  ports:
    - "127.0.0.1:9090:8080"     # 로컬만
    # - "50000:50000"           # 에이전트 연결을 안 쓰면 제거
nginx:
  ports:
    - "80:80"
    # - "443:443"               # TLS를 설정하기 전에는 제거
```

```nginx
# default.conf: 관리·문서 경로 차단 (deploy.sh의 헬스체크는 컨테이너에 직접 접근하므로 영향 없음)
location ~ ^/(actuator|h2-console|v3/api-docs) { return 404; }

# /jenkins/, /n8n/ 은 내부에서만
location /jenkins/ { allow 127.0.0.1; deny all; ... }
location /n8n/     { allow 127.0.0.1; deny all; ... }
```

- Swagger UI를 포트폴리오에서 보여 주고 싶다면 `swagger-ui`는 차단 대상에서 빼고, 운영에는 IP 제한을 두세요.
- **Jenkins root:** 호스트가 Windows(Docker Desktop)이면 소켓 권한 방식이 달라 `user: root`를 빼는 방법이 통하지 않을 수 있습니다. 동작을 확인하며 적용하고, 대안으로 `docker-socket-proxy`로 필요한 API만 여는 방법이 있습니다.

### I3. 이미지 버전 고정과 헬스체크

> **현재** **변경** — n8n은 `2.39.9`, Jenkins는 `2.568.3-lts-jdk21` 기반 사용자 이미지로 고정했고 세 서비스 모두 헬스체크가 있습니다. nginx는 `stable-alpine` 채널 태그라 정확한 버전까지 고정하지는 않았습니다.

**문제** `nginx:alpine`, `n8nio/n8n:latest`, `jenkins/jenkins:lts`가 고정되어 있지 않아 재현성이 낮습니다. 컴포즈에 헬스체크가 없어서 `depends_on`이 "기동 순서"만 보장합니다.

**수정**

- 현재 잘 동작하는 버전을 `docker image inspect`나 `docker images`로 확인해서 **그 태그로 고정**하세요.
- 헬스체크 예:

  ```yaml
  nginx:
    healthcheck:
      test: ["CMD", "wget", "-qO-", "http://localhost/health"]
  n8n:
    healthcheck:
      test: ["CMD-SHELL", "wget -qO- http://localhost:5678/healthz || exit 1"]
  ```

- `depends_on`에 `condition: service_healthy`를 사용하세요.

### I4. Nginx 기동 견고성 (`host not found in upstream`)

> **현재** **제거** — `/n8n/`, `/jenkins/` location을 없애 해당 없습니다. 백엔드는 처음부터 변수 방식이라 `nginx -t`가 컨테이너 없이 통과합니다.

**문제** `/n8n/`, `/jenkins/`는 `proxy_pass http://yak-allim-n8n:5678/;`처럼 **호스트 이름을 고정 문자열**로 씁니다. 해당 컨테이너가 없으면 Nginx가 `host not found in upstream`으로 기동에 실패합니다. 백엔드에는 `resolver` + 변수를 잘 써 두셨는데 이 두 곳은 그렇지 않습니다.

**수정** 변수와 `rewrite`로 바꾸세요.

```nginx
location /n8n/ {
    set $n8n_upstream http://yak-allim-n8n:5678;
    rewrite ^/n8n/(.*)$ /$1 break;
    proxy_pass $n8n_upstream;
    ...
}
```

이렇게 하면 4번 CI의 `nginx -t` 검증(I8)도 컨테이너 없이 통과합니다.

### I5. 배포 상태 파일이 Git 작업 트리를 더럽힘

> **현재** **유지** — `service-url.inc`는 git에서 빠져 있고 `scripts/init-nginx.sh`가 예시 파일로 만듭니다.

**문제** `nginx/conf.d`가 컨테이너에 마운트되어 있고 `deploy.sh`가 `service-url.inc`를 덮어씁니다. 그래서 **배포할 때마다 저장소의 추적 파일이 변경**됩니다.

**수정**

- `service-url.inc`는 `.gitignore`에 넣고, 저장소에는 `service-url.inc.example`을 두세요. `scripts/init-volumes.sh`가 이미 없으면 생성하는 로직이 있습니다.

### I6. 롤백과 진행 중 작업

> **현재** **유지** — 빌드 번호 태그, 최근 5개 보존, `scripts/rollback.sh`, 드레이닝이 그대로입니다. 작업 상태 영속화는 하지 않아 "무중단은 신규 요청 기준"이라는 한계도 그대로입니다.

**문제**

- 이미지가 항상 `yak-allim-backend:latest`로 덮어써져서, 이전 버전 이미지로 **되돌릴 수 없습니다.** (구 컨테이너도 전환 직후 삭제)
- 전환 직후 구 컨테이너를 곧바로 종료하는데 작업 상태가 메모리에만 있어(S2) 진행 중 작업이 사라집니다. **"무중단"은 신규 요청 기준**이라고 쓰는 편이 정확합니다.

**수정**

1. **빌드 번호 태그:** `Jenkinsfile`의 `IMAGE_NAME`을 `yak-allim-backend:${BUILD_NUMBER}`로 하고, 최근 N개 이미지만 남기세요. 롤백 스크립트(이전 태그로 색상 전환)를 추가하세요.
2. **드레이닝:** 전환 후 구 컨테이너 종료 전에 대기 시간(예: 30~60초)을 두고, `docker stop -t 70`으로 **graceful shutdown**을 유도하세요.
3. 서버에 `server.shutdown=graceful`과 `spring.lifecycle.timeout-per-shutdown-phase=60s`를 설정하세요. (S10)
4. 근본 해결은 작업 상태 영속화입니다. (S2의 선택 사항)

> `deploy.sh`의 `docker cp`(모델·키 주입)는 Jenkins가 컨테이너에서 도는 Docker-outside-of-Docker 구조에서 호스트 경로 문제를 피하려는 **의도된 우회**로 보입니다. 굳이 바꾸지 않아도 됩니다.

### I7. n8n 워크플로우 버전 관리

> **현재** **변경(확장)** — 워크플로우를 계속 이 파일로 관리합니다. 2차에서 실패 콜백·결과 검증, 재시도와 대체 모델, `null` 규칙, 온도 0이 더해졌습니다. n8n 2.x는 Publish해야 실행 버전이 바뀌므로 import·수정 뒤 반드시 게시합니다.

**문제** 하이브리드 모드는 n8n 워크플로우에 의존하는데, 저장소에 **워크플로우 정의가 없어서** 재현할 수 없습니다.

**수정** `n8n/workflows/ocr.json`으로 export해서 커밋하세요. 자격 증명, 토큰, URL의 비밀 값은 반드시 제거하고, README에 import 방법을 적으세요.

### I8. 인프라 CI 추가

> **현재** **유지** — `infra-lint` 워크플로우가 그대로이고 checkout 액션만 v7로 올렸습니다(infra #18).

`.github/workflows/infra-lint.yml`:

```yaml
name: infra-lint
on: [push, pull_request]
jobs:
  validate:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v5
      - name: compose 검증
        run: cp .env.example .env && docker compose config -q
      - name: nginx 설정 검증
        run: |
          docker run --rm \
            -v "$PWD/nginx/nginx.conf:/etc/nginx/nginx.conf:ro" \
            -v "$PWD/nginx/conf.d:/etc/nginx/conf.d:ro" \
            nginx:alpine nginx -t
      - name: 스크립트 검증
        run: shellcheck scripts/*.sh
```

`nginx -t`는 I4를 고치기 전에는 호스트 이름 해석 오류로 실패합니다.

### I9. 문서와 환경 파일

> **현재** **유지** — 포트 표, 구성도, 보안 주의, 롤백, 저장소 간 배포 흐름이 README에 있습니다. 2차에서 ngrok 공개 방식과 Android 배포 흐름을 더했습니다(infra #15, #22).

- `.env.example`의 기본 경로가 Windows(`C:/n8n_data`, `C:/yakallim-deploy`)입니다. 플랫폼별 예시를 함께 적으세요.
- README에 **포트 표, 서비스 구성도, 보안 주의(로컬 개발용), 롤백 방법**을 추가하세요.
- 서버·인프라 두 저장소 사이의 배포 흐름(GitHub Actions → Jenkins 웹훅 → `deploy.sh`)을 그림 한 장으로 정리하세요. GitHub 호스티드 러너는 로컬 Jenkins에 접근할 수 없으므로 `JENKINS_WEBHOOK_URL`이 어떻게 노출되는지(터널 등)도 설명하세요.

---

## 4. 저장소 공통 (X)

### X1. API 계약 문서화와 계약 테스트

> **현재** **변경** — 계약 문서는 n8n 단일 경로 기준으로 다시 썼습니다. 진행 단계는 `ACCEPTED`, `ANALYZING`, `COMPLETED`, `FAILED`, `CANCELLED`이고, 서버 `ApiContractTest`와 앱 `OcrMappersTest`·`ServerContractParsingTest`가 확인합니다.

- 서버 저장소에 `docs/api-contract.md`를 두고 엔드포인트, **진행 단계 코드 목록**(ACCEPTED, IMAGE_PROCESSING, TEXT_DETECTION, TEXT_RECOGNITION, PARSING, COMPLETED, FAILED), SSE 이벤트 이름(`connect`, `progress`), FCM 페이로드 키를 적으세요.
- 서버: 이 목록과 `PipelineStep.entries`가 같은지 확인하는 테스트
- 앱: 같은 목록이 모두 매핑되는지 확인하는 테스트 (A1)
- Swagger에서 `PipelineStep`을 enum 스키마로 노출

**연결 서사** 사이트플릿에서 겪은 "명세와 실제 응답의 불일치" 문제를 약올림에서는 **양쪽을 직접 관리하며 계약 테스트로 막았다**고 쓸 수 있습니다. 실제로 이 작업을 완료한 뒤에만 쓰세요.

### X2. 응답에 필드 추가 (S1, A5, A6과 함께)

> **현재** **제거** — 사전 교정이 없어져 세 필드를 응답에서 뺐습니다(server #27, android #18). 대신 값이 없으면 `null`로 보내는 규칙과 같은 항목 합치기 규칙을 계약 문서에 적었습니다.

약품 항목에 아래를 추가하세요.

| 필드 | 의미 |
| --- | --- |
| `rawName` | OCR이 읽은 원문 |
| `autoCorrected` | 사전 매칭으로 이름이 바뀌었는지 |
| `confidence` | 이름 인식 신뢰도 |

앱은 이 값으로 "자동 교정됨"과 "낮은 신뢰도"를 표시하고, 없는 값은 A5처럼 "확인 필요"로 둡니다.

### X3. 개인정보와 샘플 데이터

> **현재** **유지·주의 추가** — 2차부터 처방전 이미지가 외부 서비스(Upstage OCR, Gemini)로 전송됩니다. README의 Known Limitations에 적었고, 실기기 확인에 쓴 사진과 캡처는 확인 후 지웁니다.

- 저장소, README, 시연 영상, 스크린샷에 **실제 처방 정보가 없는지** 확인하세요. 이름, 병원, 주민번호 일부, 약품 조합이 그 자체로 민감합니다.
- 샘플 이미지는 합성하거나 가려서 쓰세요.
- 서버(S3, 이미지 삭제), 앱(A7, 백업 제외), 인프라(I2, 노출 제한)를 하나의 **개인정보 처리 방침 절**로 README에 요약하면 좋습니다.

---

## 5. 체크리스트

1차(2026-09-21)에는 모든 항목을 완료했습니다. "현재"는 2026-10-19 기준이고, 뜻은 맨 위 표와 같습니다.

**서버**

| 항목 | 내용 | 1차 | 현재 |
| --- | --- | --- | --- |
| S1 | 약품명 매칭 규칙 + 테스트 | 완료 | 제거 |
| S2 | 작업 만료·타임아웃 | 완료 | 유지 |
| S3 | 처방전 이미지 삭제 | 완료 | 변경 |
| S4 | 웹훅 시크릿, 401, 토큰 로그 | 완료 | 유지 |
| S5 | 취소 예외 분리, 엔진 미준비 오류 | 완료 | 제거 |
| S6 | 좌표 비율 정규화 | 완료 | 제거 |
| S7 | 테스트 보강 | 완료 | 변경 |
| S8 | n8n 모드 일관성 | 완료 | 제거 |
| S9 | README·About 정리 | 완료 | 유지 |
| S10 | 비동기 실행기·graceful shutdown | 완료 | 유지 |

**안드로이드**

| 항목 | 내용 | 1차 | 현재 |
| --- | --- | --- | --- |
| A1 | 진행 상태 매핑 | 완료 | 변경 |
| A2 | 재부팅 알람 복구 | 완료 | 유지 |
| A3 | ViewModel 분리와 약품 ID | 완료 | 유지 |
| A4 | 이미지 디코딩(inSampleSize) | 완료 | 유지 |
| A5 | 알 수 없는 값의 기본값 제거 | 완료 | 변경(강화) |
| A6 | 신뢰도·자동 교정 표시 | 완료 | 제거 |
| A7 | Manifest·네트워크 설정·README | 완료 | 유지 |
| A8 | 테스트 | 완료 | 변경 |

**인프라**

| 항목 | 내용 | 1차 | 현재 |
| --- | --- | --- | --- |
| I1 | Nginx 경로·업로드 한도 | 완료 | 유지 |
| I2 | 노출·보안 | 완료 | 변경 |
| I3 | 이미지 고정·헬스체크 | 완료 | 변경 |
| I4 | upstream 변수화 | 완료 | 제거 |
| I5 | 배포 상태 파일 gitignore | 완료 | 유지 |
| I6 | 롤백·드레이닝 | 완료 | 유지 |
| I7 | n8n 워크플로우 export | 완료 | 변경(확장) |
| I8 | 인프라 CI | 완료 | 유지 |
| I9 | 문서 | 완료 | 유지 |

**공통**

| 항목 | 내용 | 1차 | 현재 |
| --- | --- | --- | --- |
| 0장 | 과거 커밋 비밀 값 점검 | 완료 | 1차 이후 다시 점검하지 않음 |
| X1 | API 계약 문서·테스트 | 완료 | 변경 |
| X2 | 응답 필드 추가 | 완료 | 제거 |
| X3 | 개인정보·샘플 데이터 점검 | 완료 | 유지·주의 추가 |

**2차 이후 남은 일**

- [ ] 작업 상태 영속화 (S2 선택 사항, I6의 근본 해결)
- [ ] 알람: 시간대·시각 변경 브로드캐스트 처리 (A2의 4번)
- [ ] Jenkins 권한 축소 (`docker-socket-proxy` 등, I2)
- [ ] nginx 이미지를 정확한 버전 태그로 고정 (I3)
- [ ] 오래 남겨 둔 server·android 브랜치를 한 번에 정리 (`archive/onnx-local-ocr` 등)
