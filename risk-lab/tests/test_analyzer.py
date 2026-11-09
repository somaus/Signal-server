import io
import os
from pathlib import Path

import numpy as np
import pytest
from PIL import Image

from analyzer import FaceAnalyzer, InvalidImage, decode, _sharpness


def png(img: Image.Image) -> bytes:
    buf = io.BytesIO()
    img.save(buf, "PNG")
    return buf.getvalue()


def test_빈_이미지에서는_얼굴이_없다():
    result = FaceAnalyzer().analyze(png(Image.new("RGB", (320, 240), (120, 130, 140))))
    assert result["faces"] == []
    assert (result["width"], result["height"]) == (320, 240)
    assert result["model"] == "yunet"


def test_잡음_이미지에서도_얼굴을_지어내지_않는다():
    rng = np.random.RandomState(0)
    noise = Image.fromarray(rng.randint(0, 255, (360, 480, 3), dtype=np.uint8))
    assert FaceAnalyzer().analyze(png(noise))["faces"] == []


def test_깨진_바이트는_InvalidImage():
    with pytest.raises(InvalidImage):
        FaceAnalyzer().analyze(b"not an image")


def test_투명_PNG는_흰_배경에_합성해_읽는다():
    img = Image.new("RGBA", (10, 10), (0, 0, 0, 0))
    assert decode(png(img)).getpixel((0, 0)) == (255, 255, 255)


def test_EXIF_회전을_반영한_크기를_쓴다():
    img = Image.new("RGB", (200, 100), (10, 20, 30))
    exif = img.getexif()
    exif[0x0112] = 6   # 시계 방향 90도
    buf = io.BytesIO()
    img.save(buf, "JPEG", exif=exif)
    assert decode(buf.getvalue()).size == (100, 200)


def test_선명도는_흐린_이미지가_더_낮다():
    rng = np.random.RandomState(1)
    sharp = rng.randint(0, 255, (256, 256), dtype=np.uint8)
    import cv2
    blurry = cv2.GaussianBlur(sharp, (0, 0), 6)
    assert _sharpness(blurry, 128) < _sharpness(sharp, 128) / 20


def test_정면도는_코가_가운데일수록_높다():
    def row(nose_x):
        # x y w h, 오른쪽 눈, 왼쪽 눈, 코, 입 2개, 신뢰도
        r = np.zeros(15, dtype=np.float32)
        r[:4] = [10, 10, 100, 100]
        r[4:6] = [35, 40]; r[6:8] = [75, 40]; r[8:10] = [nose_x, 60]
        r[10:12] = [40, 85]; r[12:14] = [70, 85]; r[14] = 0.9
        return r
    gray = np.full((200, 200), 128, dtype=np.uint8)
    front = FaceAnalyzer._measure(row(55), 1.0, 200, 200, gray)
    turned = FaceAnalyzer._measure(row(75), 1.0, 200, 200, gray)
    assert front.frontal == pytest.approx(1.0)
    assert turned.frontal < 0.2
    assert front.area_ratio == pytest.approx(100 * 100 / (200 * 200))
    assert front.face_px == 100


@pytest.mark.skipif(not os.environ.get("RISK_TEST_FACE_IMAGE"), reason="얼굴 사진 경로(RISK_TEST_FACE_IMAGE)를 주면 실행한다")
def test_실제_얼굴_사진에서_얼굴을_찾는다():
    result = FaceAnalyzer().analyze(Path(os.environ["RISK_TEST_FACE_IMAGE"]).read_bytes())
    assert len(result["faces"]) >= 1
    face = result["faces"][0]
    assert 0 < face["areaRatio"] <= 1 and 0 <= face["frontal"] <= 1


def test_이미지_밖에서_시작하는_박스는_이미지_안으로_제한된다():
    gray = np.full((200, 200), 128, dtype=np.uint8)
    r = np.zeros(15, dtype=np.float32)
    r[:4] = [250, 150, 80, 80]  # 오른쪽 가장자리 밖에서 시작해 아래로도 넘치는 박스
    r[4:6] = [260, 170]; r[6:8] = [290, 170]; r[8:10] = [275, 190]; r[14] = 0.9
    face = FaceAnalyzer._measure(r, 1.0, 200, 200, gray)
    assert face.x < 200 and face.x + face.width <= 200
    assert face.y + face.height <= 200
