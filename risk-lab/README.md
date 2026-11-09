# risk-lab: 위험도 분석 측정 서비스

사진의 위험도 진단(`risk-analysis.mode=http`)에 쓰는 측정 서비스다. LLM에게 점수를 묻지 않고, 이미지에서 직접 잴 수 있는 값을 재서 돌려준다.
서버(`HttpRiskAnalyzer`)가 이 측정값을 `RiskScoring`의 고정 공식으로 점수로 바꾼다. 같은 이미지는 항상 같은 결과가 나온다.

## 무엇을 재는가

- 얼굴 위치와 신뢰도: YuNet(OpenCV Model Zoo `face_detection_yunet_2023mar`, MIT, 약 0.2MB). 학습을 새로 한 모델이 아니라 공개 모델을 그대로 쓴다.
- 얼굴마다: 박스, 이미지 대비 면적 비율, 짧은 변 픽셀 수, 선명도(128x128로 줄인 얼굴의 라플라시안 분산), 정면도(코끝이 두 눈 가운데에서 벗어난 정도, 1이 정면), 평균 밝기.
- 이미지 전체: 크기, 메가픽셀. EXIF 회전을 반영한 보이는 방향 기준 좌표를 낸다.
- 검출과 측정은 디코딩 직후 긴 변 2048px 이하로 줄인 이미지에서 하고, 박스와 얼굴 픽셀 수는 원본 기준으로 되돌려 낸다(큰 사진을 원본 크기로 여러 벌 복사하지 않으려고).

점수는 여기서 매기지 않는다. 점수 공식과 한계는 `docs/risk-analysis.md`.

## 실행

```
pip install -r requirements-dev.txt
python -m pytest
uvicorn app:app --port 8000
curl -F file=@photo.jpg localhost:8000/analyze
```

Docker Compose에서는 기본 배포에 포함되지 않고 프로필로 켠다: `docker compose --profile risk up -d --build risk`
그리고 서버에 `RISK_ANALYSIS_MODE=http`를 준다(기본값은 `llm`).

## API

- `GET /health`
- `POST /analyze` (multipart `file`) → `{model, modelVersion, width, height, megapixels, faceCount, faces:[{box:{x,y,width,height}, confidence, areaRatio, facePx, sharpness, frontal, brightness}]}`. 얼굴은 면적이 큰 순서로 최대 `RISK_MAX_FACES`(기본 10)개이고, `faceCount`는 검출한 전체 수다. 개수 제한은 이 서비스에서만 한다.
  빈 파일·읽을 수 없는 파일 400, 15MB 초과(`RISK_MAX_BYTES`) 또는 4천만 화소 초과(`RISK_MAX_PIXELS`) 413, 분석 자리를 5초(`RISK_QUEUE_TIMEOUT_SECONDS`) 안에 얻지 못하거나 호출한 쪽이 이미 끊었으면 503.

인증이 없으므로 포트를 게시하지 않고 compose 내부망의 app에서만 호출한다. 한 번에 한 장씩 처리한다(`RISK_MAX_CONCURRENCY`, 기본 1).

## 평가 세트에서 돌려 본 것

`eval_faces.py`로 딥페이크 탐지 평가 세트(164장, 얼굴 정답 라벨 없음)에 돌렸다. 정답이 없어 정확도가 아니라 현황이다. 결과는 `docs/risk-analysis.md`.
