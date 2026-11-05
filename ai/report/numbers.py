# 원본에 없는 숫자 거르기 (NF-06 · S15P31A107-163) — docs/ai-server.md §3-4와 같은 내용
# 쉬운 설명(-124)과 수정 요청문 다듬기(-125)가 같은 함수를 쓴다
import json
import logging
import math
import re
from decimal import ROUND_HALF_UP, Decimal, InvalidOperation

log = logging.getLogger("ddalkkak.report")

# 쉼표는 세 자리 묶음만, 음수 부호는 앞이 영문자 · 숫자 · _ · . 이 아닐 때만(1,240 → 1240 · 100-200은 100과 200 ·
# Java-17은 17), 지수 표기(1e-06)도 숫자로 본다 — json.dumps가 작은 float를 지수로 쓴다
_SIGN = r"(?:(?<![A-Za-z0-9_.])-)?(?<!\d)"
_NUM = re.compile(_SIGN + r"\d{1,3}(?:,\d{3})+(?:\.\d+)?|" + _SIGN + r"\d+(?:\.\d+)?(?:[eE][+-]?\d+)?")


def _key(v):
    return round(float(v), 6)  # 비교는 소수 여섯째 자리에서 반올림


def allowed_numbers(*sources):
    """허용 숫자 집합. 원본 객체들을 JSON 문자열로 바꿔 숫자를 전부 뽑고(키 이름 p95Ms의 95도 들어간다),
    값마다 변형을 더한다 — 그대로 · 정수·소수 첫째 자리 반올림(파이썬 기본과 사사오입 둘 다), 0–1 사이 값은 ×100(퍼센트)도"""
    allowed = set()
    for tok in _NUM.findall(json.dumps(sources, ensure_ascii=False)):
        d = Decimal(tok.replace(",", ""))  # 문자열로 읽어 float 오차 없이 사사오입한다
        v = float(d)
        if not math.isfinite(v):  # UUID 조각 550e8400 → 5.5e8402 같은 것. 글에 쓸 숫자가 아니다
            continue
        variants = [v, round(v), round(v, 1)]
        if 0 < v < 1:
            variants += [v * 100, round(v * 100), round(v * 100, 1)]
        for x in (d, d * 100) if 0 < v < 1 else (d,):
            try:
                variants += [x.to_integral_value(ROUND_HALF_UP), x.quantize(Decimal("0.1"), ROUND_HALF_UP)]
            except InvalidOperation:  # 28자리를 넘는 값은 quantize가 못 한다 — 반올림할 일도 없다
                pass
        allowed.update(_key(x) for x in variants)
    return allowed


def unknown_numbers(text, allowed):
    """글에서 허용 밖 숫자만 돌려준다 — 비어 있으면 통과"""
    return [tok for tok in _NUM.findall(text) if _key(float(tok.replace(",", ""))) not in allowed]


def clean_fields(fields, allowed, defaults, item_key=""):
    """LLM이 쓴 글 칸(dict) 중 허용 밖 숫자가 든 칸만 기본 문구로 바꾼다.
    로그에는 itemKey · 칸 이름 · 걸린 숫자만 남긴다 (글 전체와 키는 남기지 않는다)"""
    out = {}
    for name, text in fields.items():
        bad = unknown_numbers(text, allowed) if isinstance(text, str) else []
        if bad:
            log.warning("원본에 없는 숫자 — 기본 문구로: itemKey=%s 칸=%s 숫자=%s", item_key, name, bad)
            out[name] = defaults.get(name, "")
        else:
            out[name] = text
    return out
