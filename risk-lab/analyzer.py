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
MAX_WORK_EDGE = 2048     # 디코딩 직후 이 크기로 줄인 이미지로만 검출·측정한다. 좌표와 픽셀 수는 원본 기준으로 되돌려 낸다.
MAX_DETECT_EDGE = 1280   # 검출 입력의 긴 변 상한
MAX_FACES = int(os.environ.get("RISK_MAX_FACES", "10"))   # 돌려주는 얼굴 수의 상한(면적이 큰 순서). 개수 제한은 여기서만 한다.
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


def decode(data: bytes) -> tuple[Image.Image, tuple[int, int]]:
    """이미지를 열어 작업용 크기(긴 변 MAX_WORK_EDGE 이하)로 줄여 돌려준다. 원본 크기(회전 반영)도 함께 돌려준다.

    큰 사진을 원본 크기로 풀어 여러 벌 복사하면 메모리가 크게 늘어난다. JPEG는 디코딩 단계에서 줄여 읽고(draft),
    그 밖의 형식도 줄이는 즉시 원본 크기 비트맵을 버린다.
    """
    try:
        img = Image.open(io.BytesIO(data))
        orig_w, orig_h = img.size
        orientation = img.getexif().get(0x0112)
        if img.format == "JPEG":
            img.draft("RGB", (MAX_WORK_EDGE, MAX_WORK_EDGE))
        img.load()
        if max(img.size) > MAX_WORK_EDGE:
            img.thumbnail((MAX_WORK_EDGE, MAX_WORK_EDGE), Image.LANCZOS)
        img = ImageOps.exif_transpose(img)   # 회전 정보를 반영해 보이는 방향 기준 좌표를 낸다
    except Exception as e:  # PIL이 내는 예외 종류가 많다
        raise InvalidImage("이미지를 읽을 수 없습니다.") from e
    if orig_w < 1 or orig_h < 1 or img.width < 1 or img.height < 1:
        raise InvalidImage("빈 이미지입니다.")
    if orientation in (5, 6, 7, 8):   # 90도 회전이 들어간 사진은 가로·세로가 바뀐다
        orig_w, orig_h = orig_h, orig_w
    if img.mode in ("RGBA", "LA", "P"):
        img = img.convert("RGBA")
        bg = Image.new("RGB", img.size, (255, 255, 255))
        bg.paste(img, mask=img.getchannel("A"))
        return bg, (orig_w, orig_h)
    return img.convert("RGB"), (orig_w, orig_h)


class FaceAnalyzer:
    def __init__(self, model_path: Path = MODEL_PATH):
        self._model_path = str(model_path)
        # FaceDetectorYN은 상태(입력 크기)를 가지므로 호출마다 만든다. 모델이 작아(약 0.3MB) 부담이 적다.

    def analyze(self, data: bytes) -> dict:
        img, (width, height) = decode(data)
        work_w, work_h = img.size
        rgb = np.asarray(img)
        bgr = cv2.cvtColor(rgb, cv2.COLOR_RGB2BGR)
        gray = cv2.cvtColor(rgb, cv2.COLOR_RGB2GRAY)
        del rgb

        # 검출과 측정은 작업용 이미지에서 하고, 박스와 픽셀 수는 원본 기준으로 되돌린다.
        to_orig = width / work_w
        det_scale = min(1.0, MAX_DETECT_EDGE / max(work_w, work_h))
        det_bgr = bgr if det_scale == 1.0 else cv2.resize(
            bgr, (round(work_w * det_scale), round(work_h * det_scale)), interpolation=cv2.INTER_AREA)
        dh, dw = det_bgr.shape[:2]
        detector = cv2.FaceDetectorYN.create(self._model_path, "", (dw, dh), SCORE_THRESHOLD, 0.3, 5000)
        _, raw = detector.detect(det_bgr)

        faces: list[Face] = []
        if raw is not None:
            for row in raw:
                faces.append(self._measure(row, det_scale, work_w, work_h, gray, to_orig))
        faces.sort(key=lambda f: f.width * f.height, reverse=True)

        return {
            "model": MODEL_NAME,
            "modelVersion": MODEL_VERSION,
            "width": width,
            "height": height,
            "megapixels": round(width * height / 1e6, 3),
            "faceCount": len(faces),   # 검출한 전체 수. faces는 이 중 큰 순서로 MAX_FACES개까지
            "faces": [f.to_dict() for f in faces[:MAX_FACES]],
        }

    @staticmethod
    def _measure(row: np.ndarray, det_scale: float, work_w: int, work_h: int, gray: np.ndarray,
                 to_orig: float = 1.0) -> Face:
        """row는 검출 입력 기준 좌표, gray는 작업용 이미지. 결과의 박스와 face_px는 원본 기준이다."""
        x, y, w, h = (float(v) / det_scale for v in row[:4])   # 작업용 이미지 기준
        # 가장자리에 걸친 얼굴도 이미지 안에 최소 1x1이 남도록 자른다(이미지 밖 박스를 만들지 않는다).
        x0 = min(max(0, int(round(x))), work_w - 1)
        y0 = min(max(0, int(round(y))), work_h - 1)
        x1 = min(work_w, max(x0 + 1, int(round(x + w))))
        y1 = min(work_h, max(y0 + 1, int(round(y + h))))
        bw, bh = x1 - x0, y1 - y0
        # landmarks: 오른쪽 눈, 왼쪽 눈, 코끝, 오른쪽 입꼬리, 왼쪽 입꼬리
        lm = row[4:14].reshape(5, 2) / det_scale
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
        ox, oy = int(round(x0 * to_orig)), int(round(y0 * to_orig))
        ow, oh = max(1, int(round(bw * to_orig))), max(1, int(round(bh * to_orig)))
        return Face(
            x=ox, y=oy, width=ow, height=oh,
            confidence=float(row[14]),
            area_ratio=(bw * bh) / float(work_w * work_h),
            face_px=int(min(ow, oh)),
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
