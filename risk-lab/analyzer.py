"""얼굴 검출(YuNet)과 이미지 측정. 점수는 매기지 않고 측정값만 돌려준다(점수 공식은 서버의 RiskScoring).

측정값은 모두 픽셀에서 직접 계산한 값이다. LLM이나 학습된 위험 모델은 쓰지 않는다.
"""
from __future__ import annotations

import io
import os
from dataclasses import dataclass
from pathlib import Path

os.environ.setdefault("OPENCV_LOG_LEVEL", "ERROR")   # DNN 백엔드 안내 경고를 호출마다 찍는 것을 막는다

import cv2  # noqa: E402
import numpy as np

from PIL import Image, ImageOps

MODEL_PATH = Path(__file__).with_name("face_detection_yunet_2023mar.onnx")
MODEL_NAME = "yunet"
MODEL_VERSION = "2023mar"
MAX_DETECT_EDGE = 1280   # 검출 입력의 긴 변 상한. 측정은 원본 해상도로 한다.
SCORE_THRESHOLD = 0.6
SHARPNESS_CROP = 128     # 선명도를 얼굴 크기와 무관하게 비교하려고 정규화하는 크기


class InvalidImage(ValueError):
    pass


@dataclass
class Face:
    x: int
    y: int
    width: int
    height: int
    confidence: float
    area_ratio: float      # 얼굴 박스 면적 / 이미지 면적
    face_px: int           # 얼굴 박스의 짧은 변(원본 픽셀)
    sharpness: float       # 128x128로 줄인 얼굴 그레이 이미지의 라플라시안 분산
    frontal: float         # 0~1, 코 위치로 본 정면도(1이 정면)
    brightness: float      # 얼굴 영역 평균 밝기 0~255

    def to_dict(self) -> dict:
        return {
            "box": {"x": self.x, "y": self.y, "width": self.width, "height": self.height},
            "confidence": round(self.confidence, 4),
            "areaRatio": round(self.area_ratio, 5),
            "facePx": self.face_px,
            "sharpness": round(self.sharpness, 2),
            "frontal": round(self.frontal, 3),
            "brightness": round(self.brightness, 1),
        }


def decode(data: bytes) -> Image.Image:
    try:
        img = Image.open(io.BytesIO(data))
        img = ImageOps.exif_transpose(img)   # 회전 정보를 반영해 보이는 방향 기준 좌표를 낸다
        img.load()
    except Exception as e:  # PIL이 내는 예외 종류가 많다
        raise InvalidImage("이미지를 읽을 수 없습니다.") from e
    if img.width < 1 or img.height < 1:
        raise InvalidImage("빈 이미지입니다.")
    if img.mode in ("RGBA", "LA", "P"):
        img = img.convert("RGBA")
        bg = Image.new("RGB", img.size, (255, 255, 255))
        bg.paste(img, mask=img.getchannel("A"))
        return bg
    return img.convert("RGB")


class FaceAnalyzer:
    def __init__(self, model_path: Path = MODEL_PATH):
        self._model_path = str(model_path)
        # FaceDetectorYN은 상태(입력 크기)를 가지므로 호출마다 만든다. 모델이 작아(약 0.3MB) 부담이 적다.

    def analyze(self, data: bytes) -> dict:
        img = decode(data)
        width, height = img.size
        rgb = np.asarray(img)
        bgr = cv2.cvtColor(rgb, cv2.COLOR_RGB2BGR)
        gray = cv2.cvtColor(rgb, cv2.COLOR_RGB2GRAY)

        scale = min(1.0, MAX_DETECT_EDGE / max(width, height))
        det_bgr = bgr if scale == 1.0 else cv2.resize(
            bgr, (round(width * scale), round(height * scale)), interpolation=cv2.INTER_AREA)
        dh, dw = det_bgr.shape[:2]
        detector = cv2.FaceDetectorYN.create(self._model_path, "", (dw, dh), SCORE_THRESHOLD, 0.3, 5000)
        _, raw = detector.detect(det_bgr)

        faces: list[Face] = []
        if raw is not None:
            for row in raw:
                faces.append(self._measure(row, scale, width, height, gray))
        faces.sort(key=lambda f: f.width * f.height, reverse=True)

        return {
            "model": MODEL_NAME,
            "modelVersion": MODEL_VERSION,
            "width": width,
            "height": height,
            "megapixels": round(width * height / 1e6, 3),
            "imageSharpness": round(_sharpness(gray, 512), 2),
            "faces": [f.to_dict() for f in faces],
        }

    @staticmethod
    def _measure(row: np.ndarray, scale: float, width: int, height: int, gray: np.ndarray) -> Face:
        x, y, w, h = (float(v) / scale for v in row[:4])
        x0, y0 = max(0, int(round(x))), max(0, int(round(y)))
        x1, y1 = min(width, int(round(x + w))), min(height, int(round(y + h)))
        bw, bh = max(1, x1 - x0), max(1, y1 - y0)
        # landmarks: 오른쪽 눈, 왼쪽 눈, 코끝, 오른쪽 입꼬리, 왼쪽 입꼬리 (이미지 기준 좌표)
        lm = row[4:14].reshape(5, 2) / scale
        eye_r, eye_l, nose = lm[0], lm[1], lm[2]
        eye_dist = float(np.linalg.norm(eye_l - eye_r))
        if eye_dist < 1e-6:
            frontal = 0.0
        else:
            mid = (eye_r + eye_l) / 2.0
            # 정면이면 코끝이 두 눈의 가운데 선 위에 있다. 고개를 돌리면 눈 간격 대비 좌우로 치우친다.
            axis = (eye_l - eye_r) / eye_dist
            offset = abs(float(np.dot(nose - mid, axis))) / eye_dist
            frontal = float(max(0.0, 1.0 - offset / 0.5))
        crop = gray[y0:y1, x0:x1]
        return Face(
            x=x0, y=y0, width=bw, height=bh,
            confidence=float(row[14]),
            area_ratio=(bw * bh) / float(width * height),
            face_px=int(min(bw, bh)),
            sharpness=_sharpness(crop, SHARPNESS_CROP),
            frontal=frontal,
            brightness=float(crop.mean()) if crop.size else 0.0,
        )


def _sharpness(gray: np.ndarray, size: int) -> float:
    """라플라시안 분산. 크기에 따라 달라지지 않도록 일정한 크기로 맞춘 뒤 잰다."""
    if gray.size == 0:
        return 0.0
    resized = cv2.resize(gray, (size, size), interpolation=cv2.INTER_AREA)
    return float(cv2.Laplacian(resized, cv2.CV_64F).var())
