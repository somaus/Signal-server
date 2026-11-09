import io

import numpy as np
import pytest
from PIL import Image

from preprocess import CROP, MEAN, STD, ImageTooLarge, InvalidImage, load_image, preprocess, target_size


def png_bytes(img):
    buf = io.BytesIO()
    img.save(buf, "PNG")
    return buf.getvalue()


def test_목표_크기는_짧은_변을_440으로_맞추고_긴_변을_내림한다():
    assert target_size(640, 480) == (586, 440)   # int(440 * 640 / 480) = 586
    assert target_size(480, 640) == (440, 586)
    assert target_size(500, 500) == (440, 440)
    assert target_size(100, 100) == (440, 440)   # 작은 이미지는 키운다


def test_출력_형태와_자료형():
    out = preprocess(Image.new("RGB", (640, 480), (10, 20, 30)))
    assert out.shape == (1, 3, CROP, CROP)
    assert out.dtype == np.float32
    assert out.flags["C_CONTIGUOUS"]


def test_단색_이미지는_채널별_정규화_값이_된다():
    out = preprocess(Image.new("RGB", (300, 200), (255, 0, 128)))
    expected = (np.array([255, 0, 128], dtype=np.float32) / 255.0 - MEAN) / STD
    for channel in range(3):
        assert np.allclose(out[0, channel], expected[channel], atol=1e-5)


def test_가장자리는_잘려서_결과에_영향을_주지_않는다():
    # 1000x400 -> 1100x440으로 키운 뒤 가운데 384만 쓴다. 좌우 100px 띠(키우면 110px)는 들어오지 않는다.
    plain = Image.new("RGB", (1000, 400), (20, 40, 60))
    bordered = plain.copy()
    bordered.paste((255, 0, 0), (0, 0, 100, 400))
    bordered.paste((255, 0, 0), (900, 0, 1000, 400))
    assert np.array_equal(preprocess(plain), preprocess(bordered))


def test_가운데는_결과에_영향을_준다():
    plain = Image.new("RGB", (1000, 400), (20, 40, 60))
    marked = plain.copy()
    marked.paste((255, 255, 255), (450, 150, 550, 250))
    assert not np.array_equal(preprocess(plain), preprocess(marked))


def test_알파_채널은_RGB로_바꾼다():
    out = preprocess(Image.new("RGBA", (200, 200), (1, 2, 3, 0)))
    assert out.shape == (1, 3, CROP, CROP)


def test_load_image는_RGB로_돌려준다():
    img = load_image(png_bytes(Image.new("L", (30, 20), 7)))
    assert img.mode == "RGB" and img.size == (30, 20)


def test_이미지가_아니면_InvalidImage():
    with pytest.raises(InvalidImage):
        load_image(b"not an image")


def test_잘린_이미지는_InvalidImage():
    data = png_bytes(Image.new("RGB", (200, 200), (1, 2, 3)))
    with pytest.raises(InvalidImage):
        load_image(data[: len(data) // 2])


def test_픽셀_수_한도를_넘으면_ImageTooLarge():
    with pytest.raises(ImageTooLarge):
        load_image(png_bytes(Image.new("RGB", (100, 100))), max_pixels=9_999)
