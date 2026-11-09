import pytest

from conftest import make_image, to_bytes


def post(client, **data):
    return client.post("/protect", files={"file": ("a.png", to_bytes(make_image()), "image/png")}, data=data)


def test_level_is_case_insensitive(client):
    r = post(client, level="light", steps="2")
    assert r.status_code == 200
    assert r.headers["x-protection-level"] == "LIGHT"


@pytest.mark.parametrize("steps", ["0", "301", "-1"])
def test_steps_out_of_range_returns_422(client, steps):
    assert post(client, steps=steps).status_code == 422


def test_non_integer_steps_returns_422(client):
    assert post(client, steps="abc").status_code == 422


def test_steps_and_eps_echoed_in_headers(client):
    r = post(client, level="STRONG", steps="3")
    assert r.status_code == 200
    assert r.headers["x-steps"] == "3"
    assert r.headers["x-eps-255"] == "16"
    assert int(r.headers["x-linf-255"]) <= 16
