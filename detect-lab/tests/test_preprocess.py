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


def test_목표_크기는_극단적인_비율에서도_짧은_변이_440이다():
    assert target_size(1, 3) == (440, 1320)
    assert target_size(441, 440) == (441, 440)   # int(440 * 441 / 440) = 441
    assert target_size(439, 440) == (440, 441)   # int(440 * 440 / 439) = int(441.002)


@pytest.mark.parametrize("size", [(1, 1), (5, 3), (384, 384), (385, 1000), (3000, 17)])
def test_아주_작거나_홀수인_크기도_384_정사각으로_나온다(size):
    out = preprocess(Image.new("RGB", size, (10, 20, 30)))
    assert out.shape == (1, 3, CROP, CROP)
    expected = (np.array([10, 20, 30], dtype=np.float32) / 255.0 - MEAN) / STD
    assert np.allclose(out[0, :, 0, 0], expected, atol=1e-5)


@pytest.mark.parametrize("mode,color", [("L", 77), ("P", 0), ("CMYK", (0, 0, 0, 0))])
def test_RGB가_아닌_모드도_preprocess가_변환한다(mode, color):
    out = preprocess(Image.new(mode, (50, 50), color))
    assert out.shape == (1, 3, CROP, CROP) and out.dtype == np.float32


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


def test_픽셀_수가_한도와_같으면_통과한다():
    assert load_image(png_bytes(Image.new("RGB", (100, 100))), max_pixels=10_000).size == (100, 100)


def test_빈_바이트는_InvalidImage():
    with pytest.raises(InvalidImage):
        load_image(b"")


def test_투명_PNG는_알파를_버리고_RGB로_읽는다():
    # risk-lab과 달리 흰 배경 합성 없이 알파만 버린다(투명 픽셀의 RGB 값이 그대로 남는다).
    img = load_image(png_bytes(Image.new("RGBA", (4, 4), (1, 2, 3, 0))))
    assert img.mode == "RGB" and img.getpixel((0, 0)) == (1, 2, 3)


def test_EXIF_회전은_적용하지_않는다():
    src = Image.new("RGB", (200, 100), (10, 20, 30))
    exif = src.getexif()
    exif[0x0112] = 6
    buf = io.BytesIO()
    src.save(buf, "JPEG", exif=exif)
    assert load_image(buf.getvalue()).size == (200, 100)


def test_극단적으로_가는_이미지는_리사이즈_전에_거부한다():
    import io as _io
    from PIL import Image as _Image
    from preprocess import ImageTooLarge, load_image
    buf = _io.BytesIO()
    _Image.new("RGB", (1, 50_000)).save(buf, "PNG")
    with pytest.raises(ImageTooLarge):
        load_image(buf.getvalue())


def test_리사이즈_후_한도는_원본_한도와_따로_적용된다():
    # 100x100(1만 픽셀)은 원본 한도를 통과하지만 짧은 변 440으로 늘린 결과(19.4만 픽셀)는 전용 한도를 넘는다
    img = png_bytes(Image.new("RGB", (100, 100)))
    with pytest.raises(ImageTooLarge):
        load_image(img, max_resized_pixels=150_000)
    assert load_image(img, max_resized_pixels=200_000).size == (100, 100)


def test_넓은_파노라마는_리사이즈_후_한도_안이면_받는다():
    # 리사이즈하면 8800x440(약 3.9MP)이라 한도 안이다
    assert load_image(png_bytes(Image.new("RGB", (10_000, 500)))).size == (10_000, 500)
