# 규칙(ruleId)별 쉬운 이름 · 기본 설명 (S15P31A107-150) — AI 키가 없을 때 보이는 글. 키가 있으면 쉬운 설명(-124)이 다시 쓴다.
# order-api 실측에서 나오는 규칙부터 채웠다. 여기 없는 규칙은 도구 원문(name)으로 설명한다 (scoring.py)

_SQL_FIX = {"recommended": "값을 문자열로 잇지 말고 파라미터 바인딩(:name · ?)으로 넘기기",
            "alternative": "JPA 메서드 이름 쿼리나 Criteria로 바꾸기"}

# ZAP 주입 규칙 (ZapRunner.INJECTION과 같은 묶음)
_SQL_ZAP = {"name": "SQL 주입", "title": "검색어로 SQL을 바꿔 남의 데이터까지 읽을 수 있어요",
            "cause": "입력값이 SQL 문장에 그대로 들어가 따옴표 하나로 쿼리가 바뀌어요", "fix": _SQL_FIX}

RULES = {
    **{f"zap-{n}": _SQL_ZAP for n in ("40018", "40019", "40020", "40021", "40022", "40024")},
    "zap-90020": {"name": "명령 주입", "title": "입력값으로 서버 명령을 실행할 수 있어요",
                  "cause": "입력값이 운영체제 명령에 그대로 들어가요",
                  "fix": {"recommended": "입력값을 명령 문자열에 넣지 말고 허용 목록으로만 받기", "alternative": None}},
    "ddalkkak-idor": {"name": "남의 데이터 보기", "title": "주소의 id만 바꾸면 남의 데이터가 보여요",
                      "cause": "단건 조회에서 요청한 사람이 그 데이터의 주인인지 확인하지 않아요",
                      "fix": {"recommended": "조회할 때 로그인한 사용자로 주인을 확인하기",
                              "alternative": "남의 데이터면 404로 응답하기"}},
    "ddalkkak-admin-open": {"name": "로그인 없이 관리자 API", "title": "로그인 없이 관리자 API가 열려 있어요",
                            "cause": "관리자 경로에 로그인 · 권한 검사가 빠져 있어요",
                            "fix": {"recommended": "관리자 경로를 ADMIN 권한이 있어야만 열리게 보안 설정에 넣기",
                                    "alternative": None}},
    # Semgrep — 레지스트리 규칙은 check_id 전체, 로컬 규칙은 --config 경로가 앞에 붙는다(out.ddalkkak…). 끝부분으로 찾는다
    "formatted-sql-string": {"name": "SQL 문자열 연결", "title": "SQL을 문자열로 이어 붙여 만들어요",
                             "cause": "사용자 입력을 SQL 문장에 그대로 이어 붙여서 SQL 주입으로 이어질 수 있어요",
                             "fix": _SQL_FIX},
    "ddalkkak.java.empty-catch": {"name": "빈 catch", "title": "예외를 잡고 아무것도 하지 않아요",
                                  "cause": "빈 catch가 오류를 삼켜서 장애가 나도 로그 · 알림이 남지 않아요",
                                  "fix": {"recommended": "최소한 로그를 남기고, 여기서 처리할 수 없으면 다시 던지기",
                                          "alternative": "필요 없는 try-catch는 지우기"}},
}

# 통과 목록에 쓰는 이름 — 도구가 보낸 통과 검사(results[].passed)의 ruleId (ZAP 헤더 · 쿠키 · 주입 규칙)
PASS_NAMES = {
    "zap-10021": "X-Content-Type-Options 헤더", "zap-10020": "클릭재킹 방지 헤더", "zap-10038": "콘텐츠 보안 정책(CSP) 헤더",
    "zap-10010": "쿠키 HttpOnly", "zap-10011": "쿠키 Secure", "zap-10054": "쿠키 SameSite",
    "zap-10036": "서버 버전 노출", "zap-10037": "X-Powered-By 노출", "zap-10098": "다른 사이트 요청 허용(CORS)",
    "zap-10202": "CSRF 토큰", "zap-40012": "반사형 XSS", "zap-40014": "저장형 XSS", "zap-6": "경로 조작",
    "zap-40018": "SQL 주입", "zap-90020": "명령 주입",
}


def rule(rule_id):
    """ruleId에 맞는 설명. 같거나 '.'+키로 끝나면 찾음, 없으면 None"""
    rid = str(rule_id or "")
    return next((v for k, v in RULES.items() if rid == k or rid.endswith("." + k)), None)


def pass_name(rule_id, name):
    """아는 규칙은 한국어 이름, 아니면 도구가 준 이름 그대로 (권한 점검은 경로까지 담아 보낸다)"""
    return PASS_NAMES.get(str(rule_id or "")) or name
