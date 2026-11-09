import numpy as np
import pytest

from conftest import make_encoder, make_image
from protect_core import LEVELS, protect_image


@pytest.mark.parametrize("level", list(LEVELS))
def test_pixel_diff_bounded_by_level_eps(level):
    img = make_image(120, 90)
    r = protect_image(img, level, make_encoder(), steps=10, max_side=64)  # 축소 후 보간 경로도 거친다
    diff = np.abs(np.asarray(r.image, dtype=np.int16) - np.asarray(img, dtype=np.int16))
    eps = round(LEVELS[level]["eps"] * 255)
    assert diff.max() <= eps
    assert r.linf_255 == diff.max()
    assert r.eps_255 == pytest.approx(eps)


def test_eps_ordering_between_levels():
    assert LEVELS["LIGHT"]["eps"] < LEVELS["NORMAL"]["eps"] < LEVELS["STRONG"]["eps"]
