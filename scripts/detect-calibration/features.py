"""평가 세트 원본에서 압축·축소 변형을 만들고 모델 로짓과 압축 특징을 뽑는다. 보정(calibration) 학습·평가용."""
import csv, io, math, sys, time
from pathlib import Path
import numpy as np
from PIL import Image

import os
REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "detect-lab"))
sys.path.insert(0, str(REPO / "scripts" / "detect-eval"))
from preprocess import load_image, preprocess  # noqa
from model import OnnxModel  # noqa
import make_variants as mv  # noqa

STD_LUMA = [16,11,10,16,24,40,51,61,12,12,14,19,26,58,60,55,14,13,16,24,40,57,69,56,14,17,22,29,51,87,80,62,
            18,22,37,56,68,109,103,77,24,35,55,64,81,104,113,92,49,64,78,87,103,121,120,101,72,92,95,98,112,100,103,99]

def estimate_jpeg_quality(img):
    """JPEG 양자화 표로 품질(1~100)을 추정한다. JPEG가 아니면 None."""
    if img.format != "JPEG" or not getattr(img, "quantization", None):
        return None
    table = list(img.quantization[0])
    if len(table) != 64:
        return None
    # PIL은 지그재그 순서가 아니라 자연 순서로 주는 경우와 지그재그로 주는 경우가 있어 둘 다 비교해 가까운 쪽을 쓴다.
    best, best_q = None, None
    for q in range(1, 101):
        scale = 5000 / q if q < 50 else 200 - 2 * q
        ref = [min(255, max(1, int((v * scale + 50) // 100))) for v in STD_LUMA]
        d = sum(abs(a - b) for a, b in zip(table, ref))
        if best is None or d < best:
            best, best_q = d, q
    return best_q

def feature_row(data: bytes, session):
    img0 = Image.open(io.BytesIO(data))
    q = estimate_jpeg_quality(img0)
    w, h = img0.size
    img = load_image(data)
    logit = float(np.ravel(session.session.run(None, {session.input_name: preprocess(img)})[0])[0])
    return dict(logit=logit, jpeg=1 if q is not None else 0, quality=q if q is not None else 0, short_edge=min(w, h))

def encode(img, fmt, **kw):
    buf = io.BytesIO(); img.save(buf, fmt, **kw); return buf.getvalue()

def variants(path: Path):
    """(이름, 바이트, 평가용인가) 목록. 평가용 4종은 docs의 표와 같은 변형이다."""
    raw = path.read_bytes()
    out = [("original", raw, True)]
    img = Image.open(io.BytesIO(raw)); img.load()
    flat = mv._flatten(img)
    import tempfile, os
    for v in mv.VARIANTS:
        with tempfile.TemporaryDirectory() as td:
            p = os.path.join(td, "x." + ("jpg" if v in ("jpeg70", "messenger") else "png"))
            mv.make(img, v, p)
            out.append((v, Path(p).read_bytes(), True))
    # 학습용 추가 열화(평가용과 겹치지 않는 품질·크기)
    for q in (40, 55, 65, 80, 90, 95):
        out.append((f"aug_jpeg{q}", encode(flat, "JPEG", quality=q), False))
    for scale, q in ((0.5, 60), (0.75, 80), (0.35, 90), (0.6, 50)):
        w, h = flat.size
        small = flat.resize((max(64, round(w*scale)), max(64, round(h*scale))), Image.LANCZOS)
        out.append((f"aug_scale{scale}_q{q}", encode(small, "JPEG", quality=q), False))
    return out

def main():
    root = Path(os.environ["EVAL_ROOT"])   # images/ 와 meta.csv가 있는 폴더(평가 세트)
    rows = list(csv.DictReader(open(root / "meta.csv")))
    session = OnnxModel(os.environ["DETECT_MODEL_PATH"], "cf", "onnx-fp32", threads=2)
    out = open("features.csv", "w", newline="")
    wr = csv.DictWriter(out, ["group", "label", "variant", "eval", "logit", "jpeg", "quality", "short_edge"])
    wr.writeheader()
    t0 = time.time()
    for i, r in enumerate(rows):
        for name, data, is_eval in variants(root / r["file"]):
            f = feature_row(data, session)
            wr.writerow(dict(group=r["file"], label=1 if r["label"] == "fake" else 0, variant=name, eval=int(is_eval), **f))
        out.flush()
        print(i + 1, len(rows), round(time.time() - t0), flush=True)

if __name__ == "__main__":
    main()
