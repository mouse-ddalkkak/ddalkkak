from fastapi import FastAPI
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse


def install_error_handlers(app: FastAPI) -> None:
    """요청이 깨졌을 때만 400. 오류 모양은 openapi `BadRequest`와 같다 ({code, message})"""
    @app.exception_handler(RequestValidationError)
    async def bad_request(request, exc):
        return JSONResponse({"code": "BAD_REQUEST", "message": "요청 형식을 확인해 주세요"}, status_code=400)
