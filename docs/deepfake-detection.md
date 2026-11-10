# 딥페이크 탐지 개편 메모

## 왜 바꾸나

지금 탐지(`LlmDeepfakeDetector`)는 범용 비전 LLM이 verdict와 확신도를 함께 내놓는다. 이 모델은 진짜/가짜 이진 분류로 학습된 모델이 아니라서,
그럴듯한 설명을 붙인 채 틀릴 수 있다. 목표는 "정확도 높은 탐지"보다 "틀릴 때 틀렸다고 말하는 탐지"다.

- 판정(점수)은 전용 탐지 모델이 정하고, LLM은 결과를 풀어쓰는 설명만 맡는다. (#37)
- 모델은 서버에 올리지 않고 별도 추론 서비스(`detect-lab`)로 분리한다. (#36)
- 모델을 바꾸는 근거는 같은 평가 세트로 잰 결과다. 기준선(현재 LLM 탐지)을 넘지 못하면 바꾸지 않는다. (#33, #35)

## 모델

확인일: 2026-10-29. 모델 카드, 저장소의 LICENSE, Hugging Face 메타데이터를 직접 읽어 확인했다. 아래에서 "측정 전"은 이 서비스 입력으로 돌려 보지 않았다는 뜻이다.

| 모델 | 코드·가중치 라이선스 | 크기 | CPU 추론 | 학습 데이터 | 결정 |
|---|---|---|---|---|---|
| CommunityForensics ViT-S (`buildborderless/CommunityForensics-DeepfakeDet-ViT`) | MIT | 2,181만 파라미터. `model.safetensors` 87MB, ONNX FP32 87MB(int8 23MB, q4 16MB) | onnxruntime CPU로 구성(`detect-lab`). 공개 데이터 164장 측정에서 p50 213ms·p95 292ms(클라우드 CPU 컨테이너. 운영 서버에서는 다시 잰다) | 270만 장, 생성기 4,803종. 진짜 이미지는 LAION 중심(40%) 등 11개 데이터셋 | **1순위** |
| GenD CLIP-L/14 (`yermandy/GenD_CLIP_L_14`) | MIT(Hugging Face 카드, GitHub 저장소 LICENSE 모두) | 3.04억 파라미터. `model.safetensors` 1.2GB | 돌아가지만 ViT-S보다 훨씬 크다. 지연은 측정 전. ONNX는 제공되지 않음 | FaceForensics++ 같은 얼굴 조작 영상 벤치마크 계열 | **2순위(비교용)** |
| DEAR (`k-aisi-anti-deepfake/dear-checkpoints`) | **CC BY-NC 4.0** + CreativeML OpenRAIL-M 사용 제한 | 체크포인트 4개, 각 약 94MB(ResNet-50 기반) | 구조상 가능. 쓰지 않으므로 확인 안 함 | LSUN·MS COCO 진짜 이미지, LDM 가짜 이미지, SD 1.5 인페인팅 | **제외**(비상업) |

DEAR는 카드에 "연구 및 비상업 용도"라고 명시돼 있고 SD 1.5로 만든 데이터로 보정해서 OpenRAIL-M의 사용 제한도 진다. 서비스에 쓸 수 없다.

두 후보 모두 한국 SNS 사진 같은 이 서비스의 입력으로는 검증된 적이 없다. 그래서 #35의 평가에서 현재 LLM 탐지와 같은 세트로 비교하고, 결과로 최종 선택한다.

### 라이선스에서 따로 확인해야 하는 것

- **CommunityForensics 학습 데이터는 연구용이다.** 가중치와 코드는 MIT지만, 학습에 쓴 데이터셋(`OwensLab/CommunityForensics`)은 "CC BY 4.0, 연구 목적으로만 공개"이고, 데이터의 가짜 이미지는 각 생성 모델의 라이선스(대부분 CreativeML OpenRAIL-M)를 따른다. 진짜 이미지는 LAION 같은 웹 수집 이미지다. 가중치 라이선스(MIT)가 이를 덮는지는 이 문서가 판단하지 않는다. 상업 서비스에 쓰기 전에 팀과 법률 검토로 정해야 한다.
- **재업로드 저장소를 쓴다.** 우리가 쓰는 저장소는 원저자(Jeongsoo Park, Andrew Owens)의 공식 저장소(`OwensLab/commfor-model-384`, MIT)가 아니라 Borderless / Ethix R&D가 올린 변환본이다. 2026-10-29에 공식 저장소의 `model.safetensors`(87,262,324바이트, 커밋 `6076002`, sha256 `b89f36275f3bf5e2b040eee36597a8f19db051bff9a473a9cf7b2466284fb387`)를 받아 우리가 쓰는 ONNX(FP32)와 텐서 단위로 대조했다. 임베딩·LayerNorm·편향·선형층 가중치가 전부 같은 값이고(ONNX는 선형층을 전치하고 `qkv`를 q/k/v로 나눈 배치만 다르다), 값이 다른 텐서는 없었다. 즉 가중치 출처 문제는 "변환본이 공식과 다른가"가 아니라 아래 데이터·법률 문제만 남는다. 대조 방법은 `docs/detect-eval-results.md`.
- **GenD는 코드를 같이 가져와야 한다.** 카드의 사용 예가 저장소의 `src.hf.modeling_gend`를 불러오는 방식이고, 백본으로 `openai/clip-vit-large-patch14`를 따로 받는다. 학습 데이터(FaceForensics++ 등)의 이용 약관은 확인하지 않았다. 비교용으로만 쓰고 서비스 후보로 올리기 전에 확인한다.
- **원저자의 경고.** 데이터셋 카드는 "우리가 학습하거나 평가한 분류기는 실제 환경에서 그대로 쓰기에는 오류율이 너무 높고, 저자를 가짜 이미지 제작자로 잘못 지목하거나 생성물이 진짜로 인증되는 결과로 이어질 수 있다"고 적고 있다. 이 서비스가 점수 대신 등급과 "판단 불가"를 쓰고 이미지를 단정하지 않는 이유와 같다.
- **편향.** 데이터셋은 LAION 캡션 중심이고 생성기의 대부분이 Stable Diffusion 계열이라, 다른 계열 생성기에는 약할 수 있다고 카드가 밝히고 있다.

### 라이선스 결정 (#49)

2026-11-10에 Hugging Face의 데이터셋 카드(`OwensLab/CommunityForensics`)와 모델 카드(`OwensLab/commfor-model-384`)를 다시 확인했다.

- 데이터셋 카드는 "cc-by-4.0, 연구 목적으로만 공개"라고 적고(non-commercial이라는 단어는 쓰지 않았다), 가짜 이미지는 각 생성 모델의 라이선스를 따르며 "대부분 CreativeML OpenRAIL-M"이라고 한다. 진짜 이미지의 출처는 LAION, ImageNet, COCO, FFHQ, CelebA 등이지만 각 라이선스 조건은 카드에 없다.
- 모델 카드는 MIT만 적고, 상업 사용이나 가중치에 대한 제한은 언급하지 않는다. 학습 데이터 라이선스가 가중치에 미치는 영향도 데이터셋 카드와 모델 카드 어디에도 없다.
- 평가에만 쓰는 `CommunityForensics-Eval`은 `cc-by-nc-sa-4.0`이다. 내부 평가에만 쓰고 재배포하지 않는다는 처리는 `docs/detect-eval-results.md`와 같다.

이 문서는 법률 판단이 아니라, 공개된 카드의 문구를 근거로 개발팀이 정한 운영 결정이다. 법률 검토가 이 결정을 바꿀 수 있다.

1. **지금 용도(대회 시연, 정식 배포 없음)에서는 이 가중치를 그대로 쓴다.** 상업적 이용이 아니고, 가중치가 MIT로 공개돼 있으며, 데이터셋 카드가 연구 목적 공개라고 밝힌 범위와 어긋나지 않는다고 본다. 이 판단은 시연 서버를 대회 기간·심사 용도로만 연다는 운영 조건을 전제로 한다. `main`에 머지되면 CD가 운영 서버에 배포하므로, 일반 사용자에게 열어 두는 방식으로 바뀌면 이 결정을 다시 본다.
2. **정식 출시(상업 서비스)의 차단 조건으로 둔다.** 출시 전에 법률 검토로 "MIT 가중치를 상업 서비스에 써도 되는지"를 정해야 한다. 위 카드들은 이 질문에 답하지 않으므로 이 검토 없이 출시하지 않는다. 이 조건은 #89에서 추적한다. 검토가 "안 된다"로 끝나면 학습 데이터 라이선스가 명확한 모델로 바꾸거나, 이 서비스가 합법적으로 모은 데이터로 미세 조정한 가중치로 교체한다. 교체하면 임계값(#50)도 다시 정한다.
3. **가중치 출처는 지금의 변환본을 계속 쓴다.** 공식 가중치와 텐서 단위로 같다는 것을 대조했으므로(위 "재업로드 저장소를 쓴다") 공식 체크포인트를 직접 변환할 필요가 없다. 대신 `DETECT_MODEL_REVISION`과 `DETECT_MODEL_SHA256`으로 고정해 둔 값을 유지하고, 바꿀 때만 다시 대조한다.
4. **GenD는 서비스 후보로 올리지 않는다.** 올리기로 하면 그 전에 학습 데이터(FaceForensics++ 등)의 이용 약관부터 확인한다. 비교용으로만 쓰는 동안은 확인하지 않는다.
5. **출처 표시를 한다.** CC BY 4.0은 연구·상업 여부와 관계없이 출처 표시를 요구한다. 가중치에 이 의무가 미치는지는 불분명하지만 비용이 거의 없으므로, 앱의 오픈소스 고지(또는 README)에 CommunityForensics 데이터셋·모델의 출처, 라이선스, 변경 여부(재업로드 변환본 사용)를 적는다. 반영은 #89에서 확인한다.

### CommunityForensics 사용 시 알아둘 점 (모델 카드 기준)

- 가중치는 2026-07에 정정됐다. `model.safetensors`와 `model_fixed.safetensors`(별칭)는 정정본, `model_legacy.safetensors`와 `onnx_legacy/`는 쓰지 않는다.
  받은 `config.json`이 `num_labels=1`, `num_attention_heads=6`, `intermediate_size=1536`이 아니면 옛 파일이다.
- 전처리: RGB → 짧은 변을 440으로 → 가운데 384x384 자르기 → CLIP 정규화(mean 0.4815/0.4578/0.4082, std 0.2686/0.2613/0.2758).
  **가운데를 자르기 때문에 가장자리의 단서는 보지 못한다.** 캡처처럼 여백이 큰 이미지는 변형별 평가(`screenshot`)로 따로 본다.
- 양자화 ONNX(int8, uint8, q4)는 FP32와 점수가 크게 달라질 수 있다(카드: 최대 10~70%p). 평가·서비스에는 FP32 `onnx/model.onnx`를 기본으로 쓴다.
- 카드의 판정 기준은 확률 0.5 초과가 fake지만 다른 임계값 지침은 없다. 임계값은 #35 평가 결과로 정한다.
- 카드는 원본이 해커톤 PoC였다고 밝히고 있고 성능 수치는 싣지 않았다.
- 확인일 기준 `detect-lab`이 고정한 커밋(`ac6ee457…`)이 이 저장소의 최신이고, FP32 `onnx/model.onnx`의 SHA-256도 고정해 둔 값과 일치한다.

## 평가

`scripts/detect-eval/README.md` 참고. 평가 세트에는 실제 피해자 이미지, 실존 인물 얼굴로 만든 합성 이미지, 성적 이미지를 넣지 않는다.

## 구조 (#36)

```
Signal 서버 ── mode=llm  ──▶ LlmDeepfakeDetector ──▶ 비전 LLM (기본, 기존 동작)
             └ mode=http ──▶ HttpDeepfakeDetector ──▶ detect-lab: POST /detect ──▶ ONNX 모델
```

- 모드는 `deepfake-detection.mode`(환경변수 `DEEPFAKE_DETECTION_MODE`, 기본 `llm`)로 바꾼다. 주소와 시간 제한은 `deepfake-detection.http.base-url`, `timeout-seconds`.
- 점수(`score`, 0~1, 가짜일 확률)는 모델 출력 그대로(보정 전)다. 서버는 이를 `riskScore`(0~100)로 옮기고 등급(LOW→REAL, UNCERTAIN→SUSPICIOUS, HIGH→FAKE)에 따라 `verdict`를 정한다. 구간은 아래 "화면에 보이는 단계와 점수 구간"에 있다.
  `confidence`는 모델이 고른 쪽의 확률 `max(score, 1 - score)`이다. 등급과 판단 불가는 아래 "합성 가능성 등급과 판단 불가"를 참고.
- 이 모델은 얼굴 검출을 하지 않아 `NO_FACE_DETECTED`를 내지 않는다. 얼굴이 없는 이미지도 점수가 나온다. 근거(`evidences`)는 모델이 내지 않으며, 설명기를 켰을 때만 이미 정해진 등급의 설명이 들어간다(기본은 빈 목록).
- 영상은 지원하지 않는다(`VIDEO_NOT_SUPPORTED`). 호출 실패, 시간 초과, 범위 밖 점수는 룰 기반 값으로 대체하지 않고 `FAILED`로 처리한다.
- 서비스는 모델이 없어도 뜨며, `GET /health`가 503과 이유를 돌려준다. 자세한 실행 방법은 `detect-lab/README.md`.
- 가중치는 커밋 해시(`DETECT_MODEL_REVISION`)로 고정해서 받고, 필요하면 `DETECT_MODEL_SHA256`으로 파일을 확인한다. 평가(#35)에서 고른 해시를 그대로 운영에 쓴다.
- 추론 서비스는 서버가 받는 이미지 한도(100MB)와 같은 크기까지 받고, 그보다 크거나 64MP를 넘는 이미지는 413을 돌려준다. 이 경우 서버는 `AI_SERVER_ERROR`로 처리한다.
- `/detect`에는 인증이 없다. compose는 포트를 게시하지 않으며, 외부에 열지 않는다.

## 합성 가능성 등급과 판단 불가 (#37)

`mode=http`(전용 탐지 모델)의 결과에는 퍼센트 대신 **등급(`band`)** 을 쓴다. 화면에는 "합성 가능성 낮음 / 불확실 / 높음 / 판단 불가"로 보여 주고, "가짜 확정" 같은 표현은 쓰지 않는다.

| band | 뜻 | 정하는 기준 |
|---|---|---|
| `LOW` | 합성 가능성이 낮음(진짜라는 보증은 아님) | 점수 < `low-below` |
| `UNCERTAIN` | 점수가 중간이라 어느 쪽이라고 말하기 어려움 | `low-below` ≤ 점수 ≤ `high-above`, 또는 점수는 낮지만 AI 편집 표시(`AI_EDIT_DECLARED`)가 있음 |
| `HIGH` | 합성 가능성이 높음 | 점수 > `high-above`, 또는 강한 출처 신호 |
| `UNDETERMINED` | 판단 불가 | 짧은 변이 `min-short-edge-px`보다 작음(`undeterminedReason=LOW_RESOLUTION`) |

- 등급은 `DetectionBandPolicy`가 정한다. 순서는 강한 출처 신호 → 해상도 → 점수 → (점수가 낮을 때) AI 편집 표시이며, LLM은 관여하지 않는다.
- `deepfake-detection.bands.low-below`(10), `high-above`(70), `min-short-edge-px`(256)은 **잠정값**이다. `low-below`는 공개 데이터 평가(`docs/detect-eval-results.md`)에서 진짜 이미지가 LOW로 남는 비율(원본 99%·압축본 99~100%)과 가짜가 LOW로 새는 비율을 보고 30에서 10으로 낮췄다. `high-above`는 진짜 80장 중 HIGH가 0이었지만 표본이 작고 한국 SNS 사진이 아니라서, 오탐 비용을 생각해 70을 유지한다. `min-short-edge-px`는 이 평가에서 다루지 않았다. 실제 서비스 입력으로 다시 잰 뒤에야 확정한다. 그때까지는 이 값으로 정확도를 말하지 않는다.
- 압축이 심한 사진을 판단 불가로 보내는 규칙은 아직 없다. 어떤 신호(JPEG 품질, 메신저 전송 흔적 등)가 믿을 만한지 평가에서 확인한 뒤에 넣는다.
- 응답 필드: `band`, `assessmentBasis`(`MODEL` | `PROVENANCE`), `undeterminedReason`, `modelName`, `modelVersion`(가중치 해시 포함), `provenanceSignals`. 비전 LLM(`mode=llm`)과 이전 기록은 새 필드가 비어 있다.
- **점수 숫자는 등급이 점수에서 나왔을 때만** 준다. `UNDETERMINED`이거나 `assessmentBasis=PROVENANCE`이면 `verdict`, `confidence`, `riskScore`가 `null`이다(등급과 모순되는 숫자가 보이지 않게). 하위 호환용 `verdict`는 LOW→REAL, UNCERTAIN→SUSPICIOUS, HIGH→FAKE이고 판단 불가에는 없다. 화면은 `band`를 기준으로 그리고 `riskScore`를 퍼센트로 보여 주지 않는 것을 권장한다.

### 화면에 보이는 단계와 점수 구간

`mode=http`에서는 등급과 화면 색이 아래 구간으로 고정된다. 점수(`riskScore`)는 모델이 낸 "가짜일 확률"에 100을 곱해 반올림한 값이다.

| 화면 | 등급(`band`) | 점수 구간 | 그 밖의 조건 |
|---|---|---|---|
| 초록 "합성 가능성 낮음" | `LOW` | 0~9 | AI 편집 표시(`AI_EDIT_DECLARED`)가 있으면 `UNCERTAIN`으로 올린다 |
| 노랑 "불확실" | `UNCERTAIN` | 10~70 | 점수가 0~9여도 AI 편집 표시가 있으면 노랑이 되고, 이때는 점수가 없다(`assessmentBasis=PROVENANCE`) |
| 빨강 "합성 가능성 높음" | `HIGH` | 71~100 | 점수와 무관하게 강한 출처 신호(생성 도구 기록, AI 생성 표시)가 있어도 `HIGH` |
| 회색 "판단 불가" | `UNDETERMINED` | 점수 없음 | 짧은 변이 256px 미만 |

- 100점은 모델 확률이 99.5% 이상일 때만 나온다. "합성 확정"이라는 뜻이 아니다.
- 출처 신호로 `HIGH`가 된 경우와 `UNDETERMINED`에는 점수가 없다(`riskScore=null`). 화면은 숫자 대신 `-`를 보여준다.
- 경계값은 `deepfake-detection.bands.*` 설정이 정한다(기본 `low-below=10`, `high-above=70`). 값을 바꾸면 이 표도 같이 고친다.
- `mode=llm`(비전 LLM)에서는 이 구간이 적용되지 않는다. LLM이 `verdict`와 `riskScore`를 함께 내놓고, `verdict`가 없을 때만 서버가 30 미만 REAL, 30~70 SUSPICIOUS, 70 초과 FAKE로 정한다. 같은 점수가 다른 단계로 나올 수 있어서 구간을 보장하지 않는다.

### 출처 신호 (결정론적)

`ProvenanceSignalExtractor`가 파일에서 직접 읽는다. 모델을 쓰지 않는다.

| 신호 | 찾는 것 | 등급에 쓰는가 |
|---|---|---|
| `GENERATOR_METADATA` | PNG 텍스트 청크(Stable Diffusion WebUI의 `parameters`, ComfyUI의 `prompt`·`workflow`, InvokeAI), XMP `CreatorTool`·C2PA `softwareAgent` 필드에 적힌 생성 전용 도구 이름(Midjourney, DALL-E, Stable Diffusion, ComfyUI, NovelAI, Leonardo.Ai) | 예(강한 근거 → `HIGH`) |
| `AI_SOURCE_DECLARED` | IPTC `digitalSourceType`이 `trainedAlgorithmicMedia`(AI가 생성)임을 표시 | 예(강한 근거 → `HIGH`) |
| `AI_EDIT_DECLARED` | IPTC `digitalSourceType`이 `compositeWithTrainedAlgorithmicMedia`·`compositeSynthetic`(일부를 AI로 합성·편집), 또는 제작 도구 필드에 Adobe Firefly | 아니오(점수가 `LOW`일 때만 `UNCERTAIN`으로 올림, `assessmentBasis=PROVENANCE`) |
| `C2PA_MANIFEST` | C2PA(JUMBF) 콘텐츠 자격 증명이 포함됨 | 아니오(있다는 사실만 알림) |

- 합성(composite) 값과 Adobe Firefly는 실제로 찍은 사진에 생성형 채우기를 쓴 경우에도 남는다. 이미지 전체가 합성이라는 근거가 아니므로 `HIGH`로 올리지 않는다. 다만 일부가 AI로 만들어졌으니 모델이 낮은 점수를 줘도 "합성 가능성 낮음"이라고 하지 않고 `UNCERTAIN`으로 둔다. `algorithmicMedia`(학습 모델 없이 알고리즘으로 만든 이미지)는 AI 생성 표시로 보지 않는다.
- C2PA는 **서명을 검증하지 않는다.** 매니페스트가 있다는 것과 거기에 AI 생성 표시(`trainedAlgorithmicMedia`)가 있다는 것만 읽는다. 서명 검증은 별도 작업이다.
- 메타데이터는 스크린샷, 재저장, 메신저 전송에서 사라진다. 그래서 **신호가 없다고 진짜로 보지 않는다.** 신호가 있을 때만 근거로 쓰고, 강한 신호가 있으면 점수가 낮아도 `HIGH`(`assessmentBasis=PROVENANCE`)가 된다.
- 생성 도구 이름은 파일 전체에서 찾지 않고 `CreatorTool`, `softwareAgent` 필드 뒤 200자 안에서만 찾는다. 캡션이나 설명문에 도구 이름이 적혀 있다고 신호로 보지 않기 위해서다. 한계: XMP를 파서로 읽지 않는 문자열 검색이라 필드 이름과 값이 멀리 떨어진 형식은 놓치고(놓치면 신호 없음), EXIF `Software` 태그(바이너리)는 읽지 않는다. 목록을 늘릴 때는 평가 세트의 진짜 이미지에서 걸리는지 먼저 확인한다.

### LLM은 설명만 한다

`deepfake-detection.explain.enabled=true`(기본 `false`)이면 `LlmDeepfakeExplainer`가 이미 정해진 등급을 설명하는 문장(`evidences`)을 만든다.

- 설명기는 등급을 입력으로만 받고 결과로는 근거 목록만 돌려준다. 등급을 바꿀 방법이 없고, 실패해도 탐지 결과는 그대로 나간다(근거는 빈 목록).
- `LOW`와 `UNDETERMINED`에서는 호출하지 않는다(설명할 근거가 없는데 근거를 만들어 내지 않게).
- 프롬프트로 이미지에서 실제로 보이는 특징만 가능성 표현으로 서술하게 하고, "확정", "100%" 같은 단정 표현이 든 문장은 버린다.

## 전처리 확인

`detect-lab/preprocess.py`는 `transformers`의 `CLIPImageProcessor`(PIL 백엔드, 모델 카드의 `preprocessor_config.json` 값)와 크기가 다른 9종의 무작위 이미지에서
출력이 완전히 같음(최대 차이 0)을 확인했다(transformers 5.21, Pillow 12.3). 방향 보정(EXIF 회전)은 모델 카드의 전처리에 없어 적용하지 않는다.
