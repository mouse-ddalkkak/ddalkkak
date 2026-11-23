# S15P31A107-163 완료 조건: 시험 — 원본에 없는 숫자를 섞은 LLM 응답이 걸러진다 (NF-06 · ai-server.md §3-4)
import json
import pathlib

from report.numbers import allowed_numbers, clean_fields, unknown_numbers

RAW = json.loads(
    (pathlib.Path(__file__).resolve().parents[2] / "contracts" / "3-reports" / "reports.json")
    .read_text(encoding="utf-8")
)["calls"]["POST /ai/reports"]["request"]


def test_원본에_없는_숫자가_든_칸은_기본_문구로_바뀐다():
    allowed = allowed_numbers(RAW)
    # §3-4 시험: "p95를 300ms 이내로"의 300은 원본에 없다 → 그 칸만 기본 문구
    fields = {"title": "주문 목록이 느려요",
              "cause": "p95를 300ms 이내로 줄여야 해요",
              "impact": "요청당 DB 쿼리가 1,240번이고 커넥션 풀 대기가 148개예요"}
    defaults = {"title": "기본 제목", "cause": "기본 원인", "impact": "기본 영향"}
    out = clean_fields(fields, allowed, defaults, item_key="perf-1")
    assert out["cause"] == "기본 원인", "원본에 없는 300이 걸러지지 않았다"
    assert out["title"] == "주문 목록이 느려요", "숫자 없는 칸이 바뀌었다"
    assert out["impact"] == fields["impact"], "원본 숫자(1,240 · 148)가 걸렸다"


def test_허용_변형_쉼표_퍼센트_반올림():
    allowed = allowed_numbers({"errorRate": 0.008, "p95Ms": 487.4, "requests": 36000})
    assert unknown_numbers("오류율 0.8% · 487ms · 36,000건", allowed) == []
    assert unknown_numbers("0.49초로 줄이세요", allowed) == ["0.49"], "단위를 바꾼 숫자는 걸려야 한다"


def test_키_이름의_숫자도_허용이다():
    # §3-4: JSON 문자열로 바꿔 뽑으니 키 이름 p95Ms의 95도 들어간다
    allowed = allowed_numbers({"p95Ms": 120})
    assert unknown_numbers("p95가 120ms예요", allowed) == []


def test_경계_사사오입_지수_표기_부호():
    allowed = allowed_numbers({"p95": 148.5, "ratio": 2.25, "tiny": 0.000001, "delta": -300})
    assert unknown_numbers("약 149ms예요", allowed) == [], "사람이 반올림한 149(사사오입)가 걸렸다"
    assert unknown_numbers("2.3배 수준이에요", allowed) == [], "2.25의 사사오입 2.3이 걸렸다"
    assert unknown_numbers("0.000001 수준이에요", allowed) == [], "지수 표기(1e-06)로 저장된 원본 값이 걸렸다"
    assert unknown_numbers("-300ms 차이예요", allowed) == [], "원본 음수가 걸렸다"
    assert unknown_numbers("300ms 차이예요", allowed) == ["300"], "부호가 다른 숫자는 걸려야 한다"
    allowed = allowed_numbers({"errorRate": 0.985})
    assert unknown_numbers("오류율이 99%예요", allowed) == [], "퍼센트 변형의 사사오입(0.985→99)이 걸렸다"


def test_영문자_뒤_하이픈은_부호가_아니다():
    # Java-17 · UTF-8의 하이픈은 이름의 일부다. 원본 Java 17이 있으면 통과해야 한다
    allowed = allowed_numbers({"runtime": "Java 17", "charset": "UTF 8", "delta": -300})
    assert unknown_numbers("Java-17 · UTF-8 기준이에요", allowed) == []
    assert unknown_numbers("차이는 -300ms예요 (100-200 구간)", allowed) == ["100", "200"], "공백 뒤 부호 · 숫자 사이 하이픈은 그대로"


def test_UUID_큰_수가_원본에_있어도_예외가_나지_않는다():
    # 16진수 조각 550e8400은 지수 표기로 읽혀 inf가 되고, 28자리를 넘는 값은 Decimal 반올림이 실패했다
    allowed = allowed_numbers({"traceId": "550e8400-e29b-41d4-a716-446655440000",
                               "big": "1234567890123456789012345678901", "x": 1e30, "p95Ms": 120})
    assert unknown_numbers("p95가 120ms예요", allowed) == []
    assert unknown_numbers("300ms예요", allowed) == ["300"]


def test_빈_칸과_None은_그대로_둔다():
    out = clean_fields({"a": "", "b": None}, set(), {"a": "기본", "b": "기본"})
    assert out == {"a": "", "b": None}
