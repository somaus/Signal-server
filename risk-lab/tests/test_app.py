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
