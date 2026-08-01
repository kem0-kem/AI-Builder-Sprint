from typing import Any

from fastapi import Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from starlette.exceptions import HTTPException


class ApiError(Exception):
    def __init__(
        self,
        code: str,
        message: str,
        status_code: int,
        details: dict[str, Any] | None = None,
    ) -> None:
        super().__init__(message)
        self.code = code
        self.message = message
        self.status_code = status_code
        self.details = details or {}


async def api_error_handler(_: Request, exc: Exception) -> JSONResponse:
    if not isinstance(exc, ApiError):
        raise exc
    return JSONResponse(
        status_code=exc.status_code,
        content={
            "ok": False,
            "data": None,
            "error": {"code": exc.code, "message": exc.message, "details": exc.details},
            "meta": None,
        },
    )


async def validation_error_handler(_: Request, exc: Exception) -> JSONResponse:
    if not isinstance(exc, RequestValidationError):
        raise exc
    return JSONResponse(
        status_code=400,
        content={
            "ok": False,
            "data": None,
            "error": {
                "code": "VALIDATION_ERROR",
                "message": "요청 값을 확인해 주세요.",
                "details": {
                    "fields": [
                        {
                            "type": error.get("type"),
                            "loc": error.get("loc"),
                            "msg": error.get("msg"),
                        }
                        for error in exc.errors()
                    ]
                },
            },
            "meta": None,
        },
    )


async def http_error_handler(_: Request, exc: Exception) -> JSONResponse:
    if not isinstance(exc, HTTPException):
        raise exc
    code = "RESOURCE_NOT_FOUND" if exc.status_code == 404 else "HTTP_ERROR"
    return JSONResponse(
        status_code=exc.status_code,
        content={
            "ok": False,
            "data": None,
            "error": {"code": code, "message": str(exc.detail), "details": {}},
            "meta": None,
        },
    )
