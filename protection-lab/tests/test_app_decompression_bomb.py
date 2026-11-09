import struct
import zlib

import pytest


def png_header_only(w, h):
    """IHDR만 진짜인 PNG. Image.open은 헤더만 읽으므로 크기 검사까지 도달한다."""
    def chunk(tag, data):
        return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", zlib.crc32(tag + data))

    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0)) + chunk(b"IEND", b"")


def test_huge_header_between_limits_returns_413(client):
    # 40M < 50M 픽셀 < PIL 폭탄 한도(약 179M): 앱 자체 검사로 413
    r = client.post("/protect", files={"file": ("a.png", png_header_only(10_000, 5_000), "image/png")})
    assert r.status_code == 413


@pytest.mark.xfail(strict=True, reason="PIL DecompressionBombError가 잡히지 않아 500이 난다 (app.py 버그)")
def test_huge_header_beyond_pil_limit_returns_413(client):
    r = client.post("/protect", files={"file": ("a.png", png_header_only(20_000, 20_000), "image/png")})
    assert r.status_code == 413
