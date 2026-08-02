import logging
from collections.abc import Awaitable, Callable
from time import perf_counter

from fastapi import APIRouter, FastAPI, Request
from fastapi.exceptions import RequestValidationError
from starlette.exceptions import HTTPException
from starlette.responses import Response

from app.ai.router import router as ai_router
from app.auth.router import router as auth_router
from app.chat.router import router as chat_router
from app.common.responses import success
from app.core.config import get_settings
from app.core.errors import (
    ApiError,
    api_error_handler,
    http_error_handler,
    validation_error_handler,
)
from app.feeds.router import router as feed_router
from app.letters.router import router as letter_router
from app.meetings.router import router as meeting_router
from app.moderation.router import router as moderation_router
from app.profiles.router import router as profile_router
from app.regions.router import router as region_router
from app.reports.router import router as report_router


def create_app() -> FastAPI:
    settings = get_settings()
    application = FastAPI(title=settings.app_name, version="1.0.0")
    application.add_exception_handler(ApiError, api_error_handler)
    application.add_exception_handler(RequestValidationError, validation_error_handler)
    application.add_exception_handler(HTTPException, http_error_handler)

    @application.middleware("http")
    async def request_log(
        request: Request, call_next: Callable[[Request], Awaitable[Response]]
    ) -> Response:
        started = perf_counter()
        response = await call_next(request)
        logging.getLogger("slowtalk.http").info(
            "request_complete method=%s path=%s status=%s duration_ms=%.2f",
            request.method,
            request.url.path,
            response.status_code,
            (perf_counter() - started) * 1000,
        )
        return response

    router = APIRouter(prefix=settings.api_prefix)

    @router.get("/health", tags=["system"])
    async def health() -> dict[str, object]:
        return success({"status": "alive"})

    @router.get("/ready", tags=["system"])
    async def ready() -> dict[str, object]:
        return success(
            {"status": "ready", "moderationMode": settings.moderation_mode}
        )

    application.include_router(router)
    application.include_router(auth_router, prefix=settings.api_prefix)
    application.include_router(profile_router, prefix=settings.api_prefix)
    application.include_router(region_router, prefix=settings.api_prefix)
    application.include_router(letter_router, prefix=settings.api_prefix)
    application.include_router(chat_router, prefix=settings.api_prefix)
    application.include_router(meeting_router, prefix=settings.api_prefix)
    application.include_router(moderation_router, prefix=settings.api_prefix)
    application.include_router(feed_router, prefix=settings.api_prefix)
    application.include_router(ai_router, prefix=settings.api_prefix)
    application.include_router(report_router, prefix=settings.api_prefix)
    return application


app = create_app()
