"""위험도 분석용 측정 서비스. POST /analyze 로 이미지를 받아 얼굴 검출 결과와 측정값을 돌려준다."""
import asyncio
import io
import os

from fastapi import FastAPI, File, HTTPException, Request, UploadFile
from fastapi.concurrency import run_in_threadpool
from PIL import Image

from analyzer import FaceAnalyzer, InvalidImage

MAX_BYTES = int(os.environ.get("RISK_MAX_BYTES", 15 * 1024 * 1024))
# 해상도 상한. 컨테이너 메모리(compose의 mem_limit)와 함께 정한다. 이보다 크면 413으로 거절한다.
MAX_PIXELS = int(os.environ.get("RISK_MAX_PIXELS", 40_000_000))
# 분석 자리를 기다리는 최대 시간(초). 호출한 쪽이 이미 포기했을 시간까지 줄을 세우지 않으려고 짧게 둔다.
QUEUE_TIMEOUT = float(os.environ.get("RISK_QUEUE_TIMEOUT_SECONDS", "5"))

app = FastAPI(title="signal-risk-lab")
_analyzer = FaceAnalyzer()
_slots = asyncio.Semaphore(int(os.environ.get("RISK_MAX_CONCURRENCY", "1")))


@app.get("/health")
def health():
    return {"status": "ok"}


@app.post("/analyze")
async def analyze(request: Request, file: UploadFile = File(...)):
    data = await file.read(MAX_BYTES + 1)
    if not data:
        raise HTTPException(400, "빈 파일입니다.")
    if len(data) > MAX_BYTES:
        raise HTTPException(413, "파일이 너무 큽니다.")
    try:
        with Image.open(io.BytesIO(data)) as probe:   # 디코딩 전에 크기만 보고 거른다(압축 폭탄 방지)
            too_large = probe.width * probe.height > MAX_PIXELS
    except Exception:
        raise HTTPException(400, "이미지를 읽을 수 없습니다.")
    if too_large:
        raise HTTPException(413, "이미지 해상도가 너무 큽니다.")

    try:
        await asyncio.wait_for(_slots.acquire(), timeout=QUEUE_TIMEOUT)
    except asyncio.TimeoutError:
        raise HTTPException(503, "분석 요청이 몰려 있습니다. 잠시 후 다시 시도해 주세요.")
    try:
        # 기다리는 사이에 호출한 쪽이 끊었다면(타임아웃 등) 분석하지 않는다. 안 그러면 아무도 받지 않을 분석이 자리를 쥔다.
        if await request.is_disconnected():
            raise HTTPException(503, "요청이 취소되었습니다.")
        return await run_in_threadpool(_analyzer.analyze, data)
    except InvalidImage as e:
        raise HTTPException(400, str(e))
    finally:
        _slots.release()
