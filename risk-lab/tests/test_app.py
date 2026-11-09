import io

import numpy as np
from fastapi.testclient import TestClient
from PIL import Image

import app as app_module

client = TestClient(app_module.app)


def png_bytes(size=(64, 64)):
    buf = io.BytesIO()
    Image.new("RGB", size, (200, 200, 200)).save(buf, "PNG")
    return buf.getvalue()


def test_health():
    assert client.get("/health").json() == {"status": "ok"}


def test_analyze_얼굴이_없으면_빈_목록():
    r = client.post("/analyze", files={"file": ("a.png", png_bytes(), "image/png")})
    assert r.status_code == 200
    body = r.json()
    assert body["faces"] == [] and body["width"] == 64 and body["modelVersion"] == "2023mar"


def test_analyze_깨진_파일은_400():
    assert client.post("/analyze", files={"file": ("a.png", b"garbage", "image/png")}).status_code == 400


def test_analyze_빈_파일은_400():
    assert client.post("/analyze", files={"file": ("a.png", b"", "image/png")}).status_code == 400


def test_analyze_너무_큰_해상도는_413(monkeypatch):
    monkeypatch.setattr(app_module, "MAX_PIXELS", 100)
    assert client.post("/analyze", files={"file": ("a.png", png_bytes(), "image/png")}).status_code == 413


def test_analyze_분석_자리를_못_얻으면_503(monkeypatch):
    import asyncio
    monkeypatch.setattr(app_module, "_slots", asyncio.Semaphore(0))
    monkeypatch.setattr(app_module, "QUEUE_TIMEOUT", 0.05)
    assert client.post("/analyze", files={"file": ("a.png", png_bytes(), "image/png")}).status_code == 503


def test_analyze_응답에_전체_얼굴_수가_있다():
    body = client.post("/analyze", files={"file": ("a.png", png_bytes(), "image/png")}).json()
    assert body["faceCount"] == 0 and "imageSharpness" not in body


def test_analyze_바이트_한도를_넘으면_413_한도와_같으면_통과(monkeypatch):
    data = png_bytes()
    monkeypatch.setattr(app_module, "MAX_BYTES", len(data) - 1)
    assert client.post("/analyze", files={"file": ("a.png", data, "image/png")}).status_code == 413
    monkeypatch.setattr(app_module, "MAX_BYTES", len(data))
    assert client.post("/analyze", files={"file": ("a.png", data, "image/png")}).status_code == 200


def test_analyze_헤더만_멀쩡한_잘린_파일은_400이고_자리를_돌려준다(monkeypatch):
    import asyncio
    monkeypatch.setattr(app_module, "_slots", asyncio.Semaphore(1))
    monkeypatch.setattr(app_module, "QUEUE_TIMEOUT", 0.05)
    data = png_bytes((200, 200))
    truncated = data[: len(data) // 2]   # 크기 확인(probe)은 통과하고 디코딩에서 실패한다
    assert client.post("/analyze", files={"file": ("a.png", truncated, "image/png")}).status_code == 400
    assert client.post("/analyze", files={"file": ("a.png", data, "image/png")}).status_code == 200
