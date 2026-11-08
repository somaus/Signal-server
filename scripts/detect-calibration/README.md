# 탐지 점수 보정 실험

결과와 결론은 `docs/detect-calibration.md`. 서비스에는 적용하지 않은 실험 코드다.

```
EVAL_ROOT=<평가 세트 폴더(images/, meta.csv)> DETECT_MODEL_PATH=<model.onnx> python features.py   # features.csv 생성
python calibrate.py features.csv    # 원본 단위 5-fold 교차검증 결과(기준선 포함)
python fairbase.py features.csv     # 기준선에 임계값만 맞췄을 때(공정한 비교)
```

`features.py`는 `detect-lab`의 전처리·모델 코드와 `scripts/detect-eval/make_variants.py`를 불러 쓴다(저장소 루트 기준 상대 경로).
