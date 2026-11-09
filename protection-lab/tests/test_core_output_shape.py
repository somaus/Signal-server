import pytest
from PIL import Image

from conftest import make_encoder, make_image
from protect_core import protect_image


@pytest.mark.parametrize("mode", ["RGB", "RGBA", "L", "P", "CMYK"])
def test_output_is_rgb_with_original_size(mode):
    img = make_image(80, 50, mode=mode)
    r = protect_image(img, "LIGHT", make_encoder(), steps=2)
    assert r.image.mode == "RGB"
    assert r.image.size == (80, 50)


def test_exif_orientation_is_applied():
    img = make_image(80, 50)
    exif = Image.Exif()
    exif[0x0112] = 6  # 90도 회전
    img.info["exif"] = exif.tobytes()
    img.getexif()[0x0112] = 6
    r = protect_image(img, "LIGHT", make_encoder(), steps=2)
    assert r.image.size == (50, 80)
