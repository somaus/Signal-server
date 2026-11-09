import io
import threading
import time
from concurrent.futures import ThreadPoolExecutor

import pytest
from fastapi.testclient import TestClient
from PIL import Image

from app import create_app
from conftest import SCALE
from model import OnnxModel, sigmoid
from preprocess import MEAN, STD


def png(color=(255, 0, 128), size=(300, 200)):
    buf = io.BytesIO()
    Image.new("RGB", size, color).save(buf, "PNG")
    return buf.getvalue()


def expected_score(color):
    channels = [(c / 255.0 - m) / s for c, m, s in zip(color, MEAN, STD)]
    return sigmoid(SCALE * sum(channels) / 3)


@pytest.fixture
def client(tiny_onnx):
    with TestClient(create_app(lambda: OnnxModel(tiny_onnx, "tiny", "t1"))) as c:
        yield c


def test_health는_모델_이름과_버전을_알려준다(client):
    body = client.get("/health").json()
    assert body["status"] == "ok" and body["model"] == "tiny" and body["modelVersion"].startswith("t1+")


@pytest.mark.parametrize("color", [(255, 0, 128), (0, 0, 0), (255, 255, 255), (90, 120, 200)])
def test_detect는_단색_이미지의_점수를_계산대로_돌려준다(client, color):
    res = client.post("/detect", files={"file": ("a.png", png(color), "image/png")})
    assert res.status_code == 200
    body = res.json()
    assert body["score"] == pytest.approx(expected_score(color), abs=1e-3)  # 모델 안의 float32 평균 누적 오차
    assert body["model"] == "tiny" and body["width"] == 300 and body["height"] == 200
    assert 0.0 <= body["score"] <= 1.0


def test_이미지가_아니면_400(client):
    res = client.post("/detect", files={"file": ("a.png", b"hello", "image/png")})
    assert res.status_code == 400


def test_파일이_한도를_넘으면_413(tiny_onnx):
    app = create_app(lambda: OnnxModel(tiny_onnx, "tiny"), max_upload_bytes=100)
    with TestClient(app) as c:
        res = c.post("/detect", files={"file": ("a.png", png(size=(200, 200)), "image/png")})
    assert res.status_code == 413


def test_픽셀이_너무_많으면_413(tiny_onnx):
    app = create_app(lambda: OnnxModel(tiny_onnx, "tiny"), max_pixels=1_000)
    with TestClient(app) as c:
        res = c.post("/detect", files={"file": ("a.png", png(size=(100, 100)), "image/png")})
    assert res.status_code == 413


def test_file_필드가_없으면_422(client):
    assert client.post("/detect", data={"x": "y"}).status_code == 422


def test_모델을_못_불러오면_health와_detect가_503(tmp_path):
    def broken():
        raise FileNotFoundError("모델 파일이 없습니다")

    with TestClient(create_app(broken)) as c:
        health = c.get("/health")
        assert health.status_code == 503
        assert "모델 파일이 없습니다" in health.json()["error"]
        assert c.post("/detect", files={"file": ("a.png", png(), "image/png")}).status_code == 503


def test_디코딩도_동시_처리_수에_묶인다(tiny_onnx, monkeypatch):
    """가장 메모리를 쓰는 디코딩이 세마포어 밖이면 큰 사진 여러 장이 동시에 메모리에 올라간다."""
    import app as app_module

    lock = threading.Lock()
    state = {"now": 0, "peak": 0}
    real = app_module.load_image

    def slow_load(data, max_pixels):
        with lock:
            state["now"] += 1
            state["peak"] = max(state["peak"], state["now"])
        time.sleep(0.05)
        try:
            return real(data, max_pixels=max_pixels)
        finally:
            with lock:
                state["now"] -= 1

    monkeypatch.setattr(app_module, "load_image", slow_load)
    with TestClient(create_app(lambda: OnnxModel(tiny_onnx, "tiny"), max_concurrency=1)) as c:
        def call(_):
            return c.post("/detect", files={"file": ("a.png", png(), "image/png")}).status_code

        with ThreadPoolExecutor(max_workers=6) as pool:
            codes = list(pool.map(call, range(6)))
    assert codes == [200] * 6
    assert state["peak"] == 1


def test_기본_업로드_한도는_서버의_이미지_한도_100MB와_같다():
    import app as app_module

    assert app_module.MAX_UPLOAD_MB == 100
