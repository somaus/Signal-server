import pytest

from conftest import make_image, to_bytes


@pytest.mark.parametrize("payload", [b"", b"not an image", b"\x89PNG\r\n\x1a\n"])
def test_non_image_returns_400(client, payload):
    r = client.post("/protect", files={"file": ("a.png", payload, "image/png")})
    assert r.status_code == 400
    assert r.json()["detail"] == "not a valid image"


def test_truncated_image_returns_400(client):
    data = to_bytes(make_image(), "JPEG")
    r = client.post("/protect", files={"file": ("a.jpg", data[: len(data) // 2], "image/jpeg")})
    assert r.status_code == 400
