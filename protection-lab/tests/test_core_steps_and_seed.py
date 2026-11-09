import numpy as np
import pytest

from conftest import make_encoder, make_image
from protect_core import LEVELS, protect_image


@pytest.mark.parametrize("steps", [0, -3])
def test_non_positive_steps_raise(steps):
    with pytest.raises(ValueError):
        protect_image(make_image(), "LIGHT", make_encoder(), steps=steps)


def test_default_steps_come_from_level(monkeypatch):
    monkeypatch.setitem(LEVELS, "LIGHT", {**LEVELS["LIGHT"], "steps": 3})
    assert protect_image(make_image(), "LIGHT", make_encoder()).steps == 3


def test_same_seed_is_deterministic():
    enc = make_encoder()
    a = protect_image(make_image(), "NORMAL", enc, steps=3, seed=7)
    b = protect_image(make_image(), "NORMAL", enc, steps=3, seed=7)
    assert np.array_equal(np.asarray(a.image), np.asarray(b.image))


def test_lowercase_level_accepted():
    assert protect_image(make_image(), "strong", make_encoder(), steps=1).level == "STRONG"
