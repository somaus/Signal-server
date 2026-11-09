"""CommunityForensics ViT-S의 전처리 (모델 카드·preprocessor_config.json 기준).

RGB -> 짧은 변을 440으로(bicubic) -> 가운데 384x384 자르기 -> 0~1 -> CLIP 평균/표준편차 정규화 -> NCHW.
transformers의 CLIPImageProcessor(size.shortest_edge=440, crop_size=384, resample=3)와 같은 결과를 내도록 맞췄다.
가운데를 자르기 때문에 가장자리의 단서는 모델에 들어가지 않는다.
EXIF 회전은 적용하지 않는다(모델 카드의 전처리에 없고, 학습·평가가 디코딩된 픽셀 기준이라서).
"""
from __future__ import annotations

import io

import numpy as np
from PIL import Image, UnidentifiedImageError

SHORT_EDGE = 440
CROP = 384
MEAN = np.array([0.48145466, 0.4578275, 0.40821073], dtype=np.float32)
STD = np.array([0.26862954, 0.26130258, 0.27577711], dtype=np.float32)
MAX_PIXELS = 64_000_000  # 압축 폭탄 방지. 휴대폰 48~50MP 사진은 받고, 디코딩 메모리(RGB 3바이트/픽셀)는 동시 처리 수로 묶는다


class InvalidImage(ValueError):
    """이미지로 읽을 수 없다."""


class ImageTooLarge(ValueError):
    """픽셀 수가 한도를 넘는다."""


def load_image(data: bytes, max_pixels: int = MAX_PIXELS) -> Image.Image:
    try:
        img = Image.open(io.BytesIO(data))
        width, height = img.size
    except (UnidentifiedImageError, OSError, ValueError) as e:
        raise InvalidImage("이미지를 읽을 수 없습니다") from e
    if width * height > max_pixels:
        raise ImageTooLarge(f"이미지가 너무 큽니다({width}x{height})")
    try:
        img.load()
        return img if img.mode == "RGB" else img.convert("RGB")  # convert는 같은 모드여도 복사하므로 거른다
    except (OSError, ValueError, Image.DecompressionBombError) as e:
        raise InvalidImage("이미지를 읽을 수 없습니다") from e


def target_size(width: int, height: int, short_edge: int = SHORT_EDGE) -> tuple[int, int]:
    """짧은 변을 short_edge로 맞춘 (너비, 높이). 긴 변은 비율을 유지하고 내림한다(transformers와 같음)."""
    short, long = (width, height) if width <= height else (height, width)
    new_long = int(short_edge * long / short)
    return (short_edge, new_long) if width <= height else (new_long, short_edge)


def preprocess(img: Image.Image) -> np.ndarray:
    """RGB PIL 이미지 -> float32 [1, 3, 384, 384]."""
    if img.mode != "RGB":
        img = img.convert("RGB")
    img = img.resize(target_size(*img.size), Image.BICUBIC)
    left = (img.width - CROP) // 2
    top = (img.height - CROP) // 2
    img = img.crop((left, top, left + CROP, top + CROP))
    arr = np.asarray(img, dtype=np.float32) / 255.0
    arr = (arr - MEAN) / STD
    return np.ascontiguousarray(arr.transpose(2, 0, 1)[None, ...], dtype=np.float32)
