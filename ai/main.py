import logging

from fastapi import FastAPI

from common.errors import install_error_handlers
from plan.api import router as plan_router
from report.api import router as report_router

# HTTP · SDK 라이브러리는 DEBUG에서 요청 · 응답 헤더를 찍는다(게이트웨이가 키를 되돌려 주면 로그에 남는다). 루트 로그 수준과 상관없이 막는다 (-173)
for name in ("httpx", "httpx2", "httpcore", "httpcore2", "openai", "anthropic"):
    logging.getLogger(name).setLevel(logging.WARNING)

# AI 서버는 아무것도 저장하지 않는다. Spring이 부를 때만 일한다.
app = FastAPI(title="ddalkkak-ai")
install_error_handlers(app)
app.include_router(plan_router)
app.include_router(report_router)


@app.get("/health")
def health():
    return {"ok": True}
