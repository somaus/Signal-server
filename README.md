# Signal Server

**AI 기반 딥페이크 피해 예방·탐지·대응 솔루션 Signal**의 백엔드 서버입니다.
(Spring Boot 3.4 / Java 21 / Gradle)

## 프로젝트 소개

생성형 AI 기술의 발전으로 누구나 손쉽게 타인의 얼굴을 합성한 딥페이크 이미지·영상을 만들 수 있게 되면서, 딥페이크를 악용한 성범죄·명예훼손·사기 피해가 빠르게 늘고 있습니다. 그러나 대부분의 사용자는 자신의 사진이 악용될 수 있다는 사실조차 인지하지 못하며, 피해가 발생한 이후에도 어디에 신고해야 하는지 몰라 대응 시점을 놓치는 경우가 많습니다.

Signal은 사용자가 SNS에 사진을 올리기 전 위험 요소를 진단하는 **예방**, 이미 유포된 사진의 도용·합성 여부를 확인하는 **탐지**, 피해 발생 시 상담부터 신고, 기관 연결까지 지원하는 **대응**을 하나로 통합한 원스톱 서비스를 목표로 합니다.

### 시스템 구성

| 모듈 | 기능 |
| --- | --- |
| 예방 | 사진 위험도 사전 진단(AI), 이미지 보호 처리(적대적 노이즈 삽입) |
| 탐지 | 얼굴 모니터링(도용 추적), 정밀 딥페이크 탐지 |
| 대응 | 익명 상담 챗봇, 신고 문서 자동 작성, 실제 기관 연결 |

## API 개요

| 도메인 | 엔드포인트 | 설명 |
| --- | --- | --- |
| Auth | `/api/v1/auth/**` | 로그인 / 회원가입 / 토큰 재발급 / 로그아웃 / 인증번호·비밀번호 재설정 |
| User | `/api/v1/users/**` | 내 정보 조회·수정·탈퇴, 프로필 이미지 업로드 |
| RiskAssessment | `/api/v1/risk-assessments` | 사진 위험도 사전 진단 (목록/단건 조회 포함) |
| Protection | `/api/v1/protections` | 이미지 보호 처리(노이즈 삽입), 결과 다운로드 |
| Monitoring | `/api/v1/monitorings` | 얼굴 모니터링(도용 추적), 탐지 결과 조회/삭제 |
| DeepfakeDetection | `/api/v1/deepfake-detections` | 정밀 딥페이크 탐지 (비로그인 5회 제한) |
| Chat | `/api/v1/chat/sessions/**` | 익명 상담 챗봇 세션·메시지·상담 요약 |
| Report | `/api/v1/reports/**` | 신고 문서 자동 작성, 증거 업로드, 최종 제출 |
| Agency | `/api/v1/agencies` | 실제 기관 연결(딥링크), 연결 이력 기록 |
| Health | `/health` | 인증 없이 접근 가능한 헬스체크 |

각 AI 연동 기능(위험도 분석, 이미지 보호, 얼굴 모니터링, 딥페이크 탐지, 챗봇)은 인터페이스로 분리되어 있습니다. 현재 상태:

| 기능 | 구현체 | 상태 |
| --- | --- | --- |
| 챗봇 | `LlmChatEngine` | Groq LLM 연동 (일반 대화 답변만 LLM 생성, 위기 감지·추천 행동/기관·단계별 안내 블록·진행 단계 완료 판정은 서버 규칙, 같은 세션 최근 대화 최대 10개를 맥락으로 함께 전달). 설계와 기관 번호 근거는 [docs/chat-guidance.md](docs/chat-guidance.md) |
| 위험도 분석 | `LlmRiskAnalyzer` | Groq 비전 LLM 연동 (이미지 품질·얼굴 노출도·노출 가능성(역검색 위험 추정, 응답에 추정치 안내가 붙음)은 LLM, 메타데이터 노출은 EXIF 직접 판독) |
| 딥페이크 탐지 | `LlmDeepfakeDetector`(기본) / `HttpDeepfakeDetector` | 기본은 Groq 비전 LLM 연동(이미지만 지원). `deepfake-detection.mode=http`이면 `detect-lab` 서비스의 전용 탐지 모델(CommunityForensics ViT-S)을 호출한다. 둘 다 영상은 미지원 |
| 이미지 보호 처리 | `NoiseImageProtector` | 서버에서 직접 픽셀 노이즈 삽입(2x2 블록 상관 노이즈, 수준별 진폭 3/6/12, PNG 저장, 실측 visualDifference). 학습 모델을 겨냥한 적대적 섭동은 아니므로 도용·합성 방지를 보장하지 않음 |
| 얼굴 모니터링 | `StubFaceMonitor` | 미구현 (Stub) — 역이미지 검색은 LLM으로 불가능, 전문 서비스(예: PimEyes류) API 연동 필요 |
| 신고 문서 | `ReportPdfRenderer` | 요청할 때 신고서 내용으로 PDF를 만들어 내려줌(PDFBox, 파일로 저장하지 않음) |

**LLM으로 실제 연동된 3개(챗봇/위험도 분석/딥페이크 탐지)의 공통 원칙**: 기본 제공자는 [Groq](https://console.groq.com)(무료 티어, OpenAI 호환 API, `GROQ_API_KEY` 필요)이며, 안전·정확성이 중요한 부분(위기 감지, 추천 문구, 메타데이터 판독)은 LLM에 맡기지 않고 계속 결정론적 로직이 담당한다. `GROQ_API_KEY` 미설정, `chat.llm.enabled`/`vision.llm.enabled`가 `false`, 또는 API 호출 실패 시 자동으로 룰 기반(Stub) 응답으로 폴백하므로 키 없이도 앱은 정상 동작한다. 관련 설정은 `application.yml`의 `chat.llm.*`, `vision.llm.*` 참고.

**전용 탐지 모델(선택)**: `deepfake-detection.mode=http`(환경변수 `DEEPFAKE_DETECTION_MODE`)로 바꾸면 `detect-lab`(FastAPI + onnxruntime, `docs/deepfake-detection.md` 참고)의 `POST /detect`를 호출한다. 판정은 모델 점수만으로 정하고 LLM은 관여하지 않는다. 얼굴 검출과 근거 영역은 아직 제공하지 않는다. 같은 평가 세트로 두 탐지기를 비교하는 도구는 `scripts/detect-eval/`에 있다.

**딥페이크 탐지의 정확도에 대한 주의**: `LlmDeepfakeDetector`가 사용하는 비전 LLM은 전문 딥페이크 탐지 모델이 아니라 범용 비전 언어 모델(Qwen 계열)이다. 딥페이크 이진 분류에 특화 학습된 모델이 아니므로, 근거를 그럴듯하게 대면서도 틀릴 수 있다. 기존 해시 기반 가짜 응답보다는 낫지만, 연구/제품 수준의 딥페이크 탐지 정확도를 기대해서는 안 된다.

## 공개 원칙 (AI 모델 · 오픈소스 · 외부 자문)

Signal은 서비스에 사용한 AI 모델, 오픈소스, 개발 과정에서 활용한 AI 도구와 외부 자문을 아래와 같이 공개합니다.

### 서비스에 사용한 AI 모델

| 기능 | 모델 | 제공 | 이용 조건 |
| --- | --- | --- | --- |
| 익명 상담 챗봇 응답 생성 | Groq Chat Completions (OpenAI 호환 API) | [GroqCloud](https://console.groq.com) 호스팅 API | GroqCloud 서비스 약관, 무료 티어 사용 |
| 사진 위험도 분석 / 딥페이크 탐지 | Groq Vision — `qwen/qwen3.8-27b` | GroqCloud가 호스팅하는 Qwen 계열 비전 모델 | GroqCloud 서비스 약관, 무료 티어 사용 |

- AI 호출이 실패하면 해시로 만든 추정 판정을 보여주지 않고 "분석하지 못함"으로 안내합니다(딥페이크 탐지는 `FAILED`, 위험도 분석은 `503 ANALYSIS_UNAVAILABLE`). `fallbackUsed` 필드는 호환을 위해 유지하며 정상 응답에서는 항상 `false`입니다.
- 딥페이크 탐지 결과는 범용 모델의 판단이므로 확정 판정이 아닌 **참고 지표**로만 제공합니다.
- 위기 감지, 추천 행동·기관 안내, 메타데이터 판독은 AI가 아닌 결정론적 룰 기반 로직이 담당합니다.

### 오픈소스

| 구분 | 패키지 | 라이선스 |
| --- | --- | --- |
| Backend | Spring Boot 3.4 | Apache-2.0 |
| Backend | JJWT 0.12.6 | Apache-2.0 |
| Backend | springdoc-openapi 2.8.4 | Apache-2.0 |
| Backend | MySQL Connector/J | GPL-2.0 (FOSS 예외 조항 적용) |
| Backend | metadata-extractor 2.21.0 | Apache-2.0 |
| Backend | Lombok | MIT |
| Frontend | Next.js 16 / React 19 | MIT |
| Frontend | TanStack Query | MIT |
| Frontend | axios | MIT |
| Frontend | Tailwind CSS v4 | MIT |
| Frontend | TypeScript | Apache-2.0 |

### 개발 과정에서 활용한 AI 도구

| 도구 | 용도 |
| --- | --- |
| Claude (Anthropic) | README·주차별 산출물·회고 문서 작성 보조 |

AI 도구가 생성한 결과물은 팀원이 직접 검토하고 수정한 뒤 반영했습니다.

### 외부 자문

| 출처 | 내용 |
| --- | --- |
| 스타프로젝트 × NAVER OGQ 마켓 코칭 | 주차별 산출물(MVP 범위, 아키텍처, AI 체크리스트, 데모)에 대한 피드백 |

### 데이터 · 보안

- API 키(`GROQ_API_KEY`)는 코드에 포함하지 않고 환경변수로 주입하며, `.env` 등 민감 설정 파일은 저장소에 커밋하지 않습니다.
- 인증·인가는 JWT + Spring Security로 처리하며, 비로그인 사용자의 딥페이크 탐지는 5회로 제한합니다.


## 응답 언어 (Accept-Language)

모든 API 요청의 `Accept-Language` 헤더로 응답 언어를 정한다. 지원 값은 `ko`, `en`, `ja`, `zh-CN`이며, 헤더가 없거나 지원하지 않는 언어(번체 중국어 포함)이면 한국어를 사용한다. `en-US`, `ja-JP`처럼 지역이 붙은 값과 `q` 선호도 순서도 처리한다.

| 대상 | 동작 |
|---|---|
| 위험도 분석 (`POST /api/v1/risk-assessments`) | AI가 쓰는 `factors[].description`과 정형 문구 `recommendations`가 요청 언어로 생성된다. |
| 위험 요인 이름 `factors[].label` | `type`과 요청 언어로 정해진다. 이전에 저장된 진단도 요청 언어로 내려간다. |
| 딥페이크 탐지 (`POST /api/v1/deepfake-detections`) | 근거 설명(`evidences[].description`)이 요청 언어로 생성된다. 요청 시점의 언어가 비동기 작업에도 전달된다. |
| 기관 조회 (`GET /api/v1/agencies`) | 기관명·운영시간·지원 내용(`supportedActions`)이 요청 언어로 나간다. 기관명은 한국어 정식 명칭 뒤에 번역을 괄호로 덧붙인다(예: `자살예방 상담전화 (Suicide Prevention Hotline)`). 전화번호·웹사이트는 언어와 무관하다. 번역은 `AgencyTexts`의 표에서 한국어 원문으로 찾으며, 표에 없는 값(직접 고치거나 새로 넣은 기관)은 DB 값 그대로 나간다. |
| 익명 상담 챗봇 | 일반 대화의 AI 답변이 요청 언어로 생성되고, 서버가 붙이는 위기 응답·단계별 안내·종료 제안·기관 목록도 요청 언어(ko/en/ja/zh-CN)로 나간다. |

한국어가 유지되는 항목: 신고 문서 초안, `/api/v1/chat/sessions/{id}/summary`의 문장, 서버 에러의 `message`. (챗봇의 위기 응답·단계별 안내·종료 제안·기관 목록은 요청 언어로 나간다.) 이미 저장된 분석·상담 기록의 문장은 저장 당시 언어로 내려간다.

응답은 요청 언어에 따라 달라지므로 서버가 `Vary: Accept-Language` 헤더를 붙인다. 프록시·CDN 앞단에서 캐시할 때 언어별로 따로 저장되게 하기 위해서다.

### 위험 요인 `type` 고정 값

`IMAGE_QUALITY`, `FACE_EXPOSURE`, `METADATA_LEAK`, `REVERSE_SEARCH_RISK` — 언어와 무관하게 고정이며, 프론트는 이 값으로 번역 키를 매칭할 수 있다.

### 에러 코드

에러 응답은 `{ "status", "code", "message" }` 형식이며 `code`는 아래 값 중 하나다. 프론트는 `code`를 기준으로 언어별 문구를 표시한다 (`message`는 한국어).

| code | HTTP |
|---|---|
| `INVALID_INPUT` | BAD_REQUEST |
| `UNAUTHORIZED` | UNAUTHORIZED |
| `FORBIDDEN` | FORBIDDEN |
| `NOT_FOUND` | NOT_FOUND |
| `CONFLICT` | CONFLICT |
| `FILE_TOO_LARGE` | PAYLOAD_TOO_LARGE |
| `UNSUPPORTED_MEDIA_TYPE` | UNSUPPORTED_MEDIA_TYPE |
| `INTERNAL_SERVER_ERROR` | INTERNAL_SERVER_ERROR |
| `LOGIN_FAILED` | UNAUTHORIZED |
| `EMAIL_ALREADY_EXISTS` | CONFLICT |
| `AGREEMENT_REQUIRED` | BAD_REQUEST |
| `CODE_MISMATCH` | BAD_REQUEST |
| `CODE_EXPIRED` | BAD_REQUEST |
| `INVALID_REFRESH_TOKEN` | UNAUTHORIZED |
| `INVALID_VERIFICATION` | UNAUTHORIZED |
| `PASSWORD_MISMATCH` | BAD_REQUEST |
| `VERIFICATION_TOO_FREQUENT` | TOO_MANY_REQUESTS |
| `FACE_NOT_DETECTED` | UNPROCESSABLE_ENTITY |
| `ANALYSIS_UNAVAILABLE` | SERVICE_UNAVAILABLE |
| `ANONYMOUS_ID_REQUIRED` | BAD_REQUEST |
| `ANONYMOUS_DETECTION_LIMIT_EXCEEDED` | TOO_MANY_REQUESTS |
| `ANONYMOUS_RISK_ASSESSMENT_LIMIT_EXCEEDED` | TOO_MANY_REQUESTS |
| `ANONYMOUS_CHAT_LIMIT_EXCEEDED` | TOO_MANY_REQUESTS |
| `PROTECTION_NOT_READY` | CONFLICT |
| `REQUIRED_FIELD_MISSING` | BAD_REQUEST |
| `REPORT_ALREADY_FINALIZED` | CONFLICT |
