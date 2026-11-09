import app as app_module
from conftest import make_image, to_bytes


def test_upload_over_limit_returns_413(client, monkeypatch):
    monkeypatch.setattr(app_module, "MAX_UPLOAD_MB", 1)
    big = b"\0" * (1024 * 1024 + 1)
    r = client.post("/protect", files={"file": ("a.png", big, "image/png")})
    assert r.status_code == 413
    assert "1MB" in r.json()["detail"]


def test_upload_exactly_at_limit_is_not_rejected_for_size(client, monkeypatch):
    monkeypatch.setattr(app_module, "MAX_UPLOAD_MB", 1)
    exact = b"\0" * (1024 * 1024)
    # 크기 검사는 통과하고, 이미지가 아니라서 400이 나와야 한다.
    assert client.post("/protect", files={"file": ("a.png", exact, "image/png")}).status_code == 400


def test_pixel_count_over_limit_returns_413(client, monkeypatch):
    monkeypatch.setattr(app_module, "MAX_PIXELS", 96 * 64 - 1)
    r = client.post("/protect", files={"file": ("a.png", to_bytes(make_image()), "image/png")})
    assert r.status_code == 413
    assert r.json()["detail"] == "image too large"
