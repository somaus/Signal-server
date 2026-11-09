import io
import os
from pathlib import Path

import numpy as np
import pytest
from PIL import Image

import analyzer as analyzer_module
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
    decoded, _ = decode(png(img))
    assert decoded.getpixel((0, 0)) == (255, 255, 255)


def test_팔레트와_LA_이미지도_흰_배경에_합성한다():
    pal = Image.new("P", (10, 10), 0)
    pal.putpalette([0, 0, 0] * 256)
    pal.info["transparency"] = 0
    la = Image.new("LA", (10, 10), (0, 0))
    for img in (pal, la):
        decoded, size = decode(png(img))
        assert decoded.mode == "RGB" and size == (10, 10)
        assert decoded.getpixel((0, 0)) == (255, 255, 255)


def test_불투명한_픽셀은_색을_유지하고_반투명은_흰색과_섞인다():
    img = Image.new("RGBA", (2, 1))
    img.putpixel((0, 0), (10, 20, 30, 255))
    img.putpixel((1, 0), (0, 0, 0, 128))
    decoded, _ = decode(png(img))
    assert decoded.getpixel((0, 0)) == (10, 20, 30)
    assert all(120 <= c <= 135 for c in decoded.getpixel((1, 0)))


def test_흑백_이미지는_RGB로_읽는다():
    decoded, _ = decode(png(Image.new("L", (8, 6), 77)))
    assert decoded.mode == "RGB" and decoded.getpixel((0, 0)) == (77, 77, 77)


def test_EXIF_회전을_반영한_크기를_쓴다():
    img = Image.new("RGB", (200, 100), (10, 20, 30))
    exif = img.getexif()
    exif[0x0112] = 6   # 시계 방향 90도
    buf = io.BytesIO()
    img.save(buf, "JPEG", exif=exif)
    decoded, original = decode(buf.getvalue())
    assert decoded.size == (100, 200)
    assert original == (100, 200)   # 원본 크기도 보이는 방향 기준


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


def _row(x, y, w, h):
    r = np.zeros(15, dtype=np.float32)
    r[:4] = [x, y, w, h]
    r[4:6] = [x + w * 0.3, y + h * 0.4]; r[6:8] = [x + w * 0.7, y + h * 0.4]; r[8:10] = [x + w * 0.5, y + h * 0.6]
    r[10:12] = [x + w * 0.35, y + h * 0.8]; r[12:14] = [x + w * 0.65, y + h * 0.8]; r[14] = 0.9
    return r


def test_가장자리에_걸친_얼굴도_이미지_안에_박스가_남는다():
    gray = np.full((200, 300), 128, dtype=np.uint8)
    cases = [(-20, -10, 60, 50), (270, 170, 60, 50), (290, 190, 40, 40), (-50, -50, 30, 30)]
    for x, y, w, h in cases:
        face = FaceAnalyzer._measure(_row(x, y, w, h), 1.0, 300, 200, gray)
        assert 0 <= face.x < 300 and 0 <= face.y < 200
        assert face.width >= 1 and face.height >= 1
        assert face.x + face.width <= 300 and face.y + face.height <= 200


def test_큰_사진은_줄여서_읽고_좌표와_크기는_원본_기준으로_낸다():
    big = Image.new("RGB", (6000, 4000), (200, 200, 200))
    decoded, original = decode(png(big))
    assert original == (6000, 4000)
    assert max(decoded.size) <= analyzer_module.MAX_WORK_EDGE
    face = FaceAnalyzer._measure(_row(512, 256, 256, 256), 1.0, decoded.width, decoded.height,
                                 np.full((decoded.height, decoded.width), 128, dtype=np.uint8),
                                 to_orig=original[0] / decoded.width)
    # 작업용 이미지의 256px 얼굴은 원본에서는 약 2.93배인 750px 안팎
    assert 700 <= face.face_px <= 800
    result = FaceAnalyzer().analyze(png(big))
    assert (result["width"], result["height"]) == (6000, 4000)
    assert result["faces"] == [] and result["faceCount"] == 0


def test_JPEG는_크기를_줄여_읽어도_원본_크기를_알려준다():
    buf = io.BytesIO()
    Image.new("RGB", (5000, 3000), (90, 100, 110)).save(buf, "JPEG", quality=80)
    result = FaceAnalyzer().analyze(buf.getvalue())
    assert (result["width"], result["height"]) == (5000, 3000)


def test_얼굴_수는_상한까지만_돌려주고_전체_수를_따로_알린다(monkeypatch):
    class FakeDetector:
        def detect(self, _img):
            rows = [_row(10 + i * 20, 10, 10 + i, 10 + i) for i in range(15)]
            return 1, np.array(rows, dtype=np.float32)

    monkeypatch.setattr(analyzer_module.cv2.FaceDetectorYN, "create", staticmethod(lambda *a, **k: FakeDetector()))
    monkeypatch.setattr(analyzer_module, "MAX_FACES", 10)

    result = FaceAnalyzer().analyze(png(Image.new("RGB", (400, 100), (120, 120, 120))))

    assert result["faceCount"] == 15
    assert len(result["faces"]) == 10
    sizes = [f["box"]["width"] for f in result["faces"]]
    assert sizes == sorted(sizes, reverse=True)
