from PIL import Image

from protect_core import ProtectResult


def test_as_headers_formats_values():
    r = ProtectResult(
        image=Image.new("RGB", (1, 1)), level="NORMAL", eps_255=8.0, steps=100, work_size=(64, 64),
        loss_start=0.123456789, loss_end=0.0000049, psnr_db=38.456, linf_255=8, seconds=1.234,
    )
    assert r.as_headers() == {
        "X-Protection-Level": "NORMAL",
        "X-Eps-255": "8",
        "X-Steps": "100",
        "X-PSNR-dB": "38.46",
        "X-Linf-255": "8",
        "X-Loss-Start": "0.12346",
        "X-Loss-End": "0.00000",
        "X-Seconds": "1.23",
    }


def test_headers_are_ascii_strings():
    r = ProtectResult(Image.new("RGB", (1, 1)), "LIGHT", 4.0, 1, (64, 64), 1.0, 0.5, 99.0, 0, 0.0)
    assert all(isinstance(v, str) and v.isascii() for v in r.as_headers().values())
