"""위험도 분석용 측정 서비스. POST /analyze 로 이미지를 받아 얼굴 검출 결과와 측정값을 돌려준다."""
import os
import asyncio
import io

from fastapi import FastAPI, File, HTTPException, UploadFile
from fastapi.concurrency import run_in_threadpool

from PIL import Image

from analyzer import FaceAnalyzer, InvalidImage

MAX_BYTES = int(os.environ.get("RISK_MAX_BYTES", 15 * 1024 * 1024))
MAX_PIXELS = int(os.environ.get("RISK_MAX_PIXELS", 24_000_000))  # 384m 컨테이너에서 원본 해상도 배열 여러 장을 버틸 수 있는 상한

app = FastAPI(title="signal-risk-lab")
_analyzer = FaceAnalyzer()
_slots = asyncio.Semaphore(int(os.environ.get("RISK_MAX_CONCURRENCY", "1")))


@app.get("/health")
def health():
    return {"status": "ok"}


@app.post("/analyze")
async def analyze(file: UploadFile = File(...)):
    data = await file.read(MAX_BYTES + 1)
    if not data:
        raise HTTPException(400, "빈 파일입니다.")
    if len(data) > MAX_BYTES:
        raise HTTPException(413, "파일이 너무 큽니다.")
    try:
        with Image.open(io.BytesIO(data)) as probe:   # 디코딩 전에 크기만 보고 거른다(압축 폭탄 방지)
            if probe.width * probe.height > MAX_PIXELS:
                raise HTTPException(413, "이미지 해상도가 너무 큽니다.")
    except HTTPException:
        raise
    except Exception:
        raise HTTPException(400, "이미지를 읽을 수 없습니다.")
    try:
        async with _slots:
            return await run_in_threadpool(_analyzer.analyze, data)
    except InvalidImage as e:
        raise HTTPException(400, str(e))
