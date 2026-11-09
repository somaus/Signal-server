"""공통 픽스처: 모델 다운로드 없이 작은 합성곱 인코더와 합성 이미지를 쓴다."""
import io
import sys
from pathlib import Path

import numpy as np
import pytest
import torch
from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))


def make_encoder():
    torch.manual_seed(0)
    net = torch.nn.Sequential(
        torch.nn.Conv2d(3, 8, 3, stride=2, padding=1),
        torch.nn.Tanh(),
        torch.nn.Conv2d(8, 4, 3, stride=2, padding=1),
    ).eval()
    for p in net.parameters():
        p.requires_grad_(False)
    return lambda x: net(x)


def make_image(w=96, h=64, seed=1, mode="RGB"):
    rng = np.random.default_rng(seed)
    img = Image.fromarray(rng.integers(40, 215, size=(h, w, 3), dtype=np.uint8))
    return img.convert(mode)


def to_bytes(img, fmt="PNG"):
    buf = io.BytesIO()
    img.save(buf, format=fmt)
    return buf.getvalue()


@pytest.fixture
def client():
    from fastapi.testclient import TestClient

    from app import create_app

    with TestClient(create_app(encoder_factory=make_encoder, device="cpu")) as c:
        yield c
