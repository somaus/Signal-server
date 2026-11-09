from protect_core import LEVELS


def test_health_reports_device_and_levels(client):
    body = client.get("/health").json()
    assert body == {"status": "ok", "device": "cpu", "levels": list(LEVELS)}


def test_protect_requires_file(client):
    assert client.post("/protect", data={"level": "LIGHT"}).status_code == 422
