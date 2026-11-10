import sys

import pytest
from PIL import Image

import cli
from conftest import make_encoder, make_image


@pytest.fixture
def no_model(monkeypatch):
    seen = {}

    def fake_loader(device):
        seen["device"] = device
        return make_encoder()

    monkeypatch.setattr(cli, "load_sd_vae_encoder", fake_loader)
    return seen


def run(monkeypatch, *args):
    monkeypatch.setattr(sys, "argv", ["cli.py", *args])
    cli.main()


def test_cli_writes_png_and_prints_metrics(tmp_path, monkeypatch, capsys, no_model):
    src, dst = tmp_path / "in.jpg", tmp_path / "out.png"
    make_image(70, 40).save(src)
    run(monkeypatch, str(src), str(dst), "--level", "STRONG", "--steps", "2", "--device", "cpu")
    out = Image.open(dst)
    assert out.format == "PNG" and out.size == (70, 40)
    assert no_model["device"] == "cpu"
    assert "level=STRONG eps=16/255 steps=2" in capsys.readouterr().out


def test_cli_auto_device_without_flag(tmp_path, monkeypatch, no_model):
    src = tmp_path / "in.png"
    make_image().save(src)
    run(monkeypatch, str(src), str(tmp_path / "o.png"), "--steps", "1")
    assert no_model["device"] in ("cpu", "cuda")


def test_cli_rejects_unknown_level(tmp_path, monkeypatch, no_model):
    with pytest.raises(SystemExit) as e:
        run(monkeypatch, "a.png", "b.png", "--level", "EXTREME")
    assert e.value.code == 2


def test_cli_requires_input_and_output(monkeypatch, no_model):
    with pytest.raises(SystemExit):
        run(monkeypatch, "only_input.png")
