import pytest

from protect_core import _work_size


@pytest.mark.parametrize(
    "w,h,max_side,expected",
    [
        (512, 512, 512, (512, 512)),
        (1024, 512, 512, (512, 256)),
        (100, 100, 512, (96, 96)),  # 확대하지 않고 8의 배수로 반올림(12.5 -> 12, 은행가 반올림)
        (30, 20, 512, (64, 64)),  # 최소 64
        (2000, 10, 512, (512, 64)),
    ],
)
def test_work_size(w, h, max_side, expected):
    assert _work_size(w, h, max_side) == expected


def test_work_size_is_multiple_of_8():
    for w, h in [(301, 203), (777, 1234), (65, 999)]:
        ww, wh = _work_size(w, h, 512)
        assert ww % 8 == 0 and wh % 8 == 0
