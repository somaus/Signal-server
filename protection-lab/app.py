"""보호 처리용 FastAPI 서버 (실험 구현, Signal 서비스에는 연결되어 있지 않음).

실행:  uvicorn app:app --host 0.0.0.0 --port 8000
요청:  curl -F file=@photo.jpg -F level=NORMAL http://localhost:8000/protect -o protected.png -D -
"""
from __future__ import annotations

import io
import os
import threading
from contextlib import asynccontextmanager
from typing import Callable, Optional

from fastapi import FastAPI, File, Form, HTTPException, UploadFile
from fastapi.responses import Response
from PIL import Image, UnidentifiedImageError

from protect_core import LEVELS, Encoder, load_sd_vae_encoder, protect_image

MAX_UPLOAD_MB = int(os.getenv("PROTECT_MAX_UPLOAD_MB", "10"))
MAX_PIXELS = 40_000_000  # 압축 폭탄 방지


def _default_device() -> str:
    forced = os.getenv("PROTECT_DEVICE")
    if forced:
        return forced
    try:
        import torch

        return "cuda" if torch.cuda.is_available() else "cpu"
    except Exception:
        return "cpu"


def create_app(encoder_factory: Optional[Callable[[], Encoder]] = None, device: Optional[str] = None) -> FastAPI:
    state: dict = {}
    lock = threading.Lock()  # 한 번에 한 건만 처리 (메모리 보호)

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        dev = device or _default_device()
        state["device"] = dev
        state["encoder"] = encoder_factory() if encoder_factory else load_sd_vae_encoder(dev)
        yield

    app = FastAPI(title="Signal Protection Lab", lifespan=lifespan)

    @app.get("/health")
    def health():
        return {"status": "ok", "device": state.get("device"), "levels": list(LEVELS)}

    @app.post("/protect")
    def protect(file: UploadFile = File(...), level: str = Form("NORMAL"), steps: Optional[int] = Form(None)):
        level = level.upper()
        if level not in LEVELS:
            raise HTTPException(status_code=422, detail=f"level must be one of {list(LEVELS)}")
        if steps is not None and not (1 <= steps <= 300):
            raise HTTPException(status_code=422, detail="steps must be between 1 and 300")

        data = file.file.read(MAX_UPLOAD_MB * 1024 * 1024 + 1)
        if len(data) > MAX_UPLOAD_MB * 1024 * 1024:
            raise HTTPException(status_code=413, detail=f"file larger than {MAX_UPLOAD_MB}MB")
        try:
            img = Image.open(io.BytesIO(data))
            if img.width * img.height > MAX_PIXELS:
                raise HTTPException(status_code=413, detail="image too large")
            img.load()
        except (UnidentifiedImageError, OSError):
            raise HTTPException(status_code=400, detail="not a valid image")

        with lock:
            result = protect_image(img, level, state["encoder"], steps=steps, device=state["device"])

        buf = io.BytesIO()
        result.image.save(buf, format="PNG")
        return Response(content=buf.getvalue(), media_type="image/png", headers=result.as_headers())

    return app


app = create_app()
