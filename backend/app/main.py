import logging
from collections.abc import Awaitable, Callable
from time import perf_counter

from fastapi import APIRouter, FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from starlette.exceptions import HTTPException
from starlette.responses import Response

from app.ai.router import router as ai_router
from app.auth.router import router as auth_router
from app.chat.router import router as chat_router
from app.common.responses import success
from app.core.config import get_settings, moderation_configuration_complete
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

    @router.get(
        "/ready",
        tags=["system"],
        response_model=dict[str, object],
        responses={
            503: {"description": "Moderation configuration is incomplete"},
        },
    )
    async def ready() -> JSONResponse:
        moderation_configured = moderation_configuration_complete(settings)
        fallback_allowed = (
            settings.app_environment in {"development", "test"}
            and settings.allow_development_moderation_fallback
        )
        is_ready = moderation_configured or fallback_allowed
        fallback_active = not moderation_configured and fallback_allowed
        return JSONResponse(
            status_code=200 if is_ready else 503,
            content=success(
                {
                    "status": "ready" if is_ready else "not_ready",
                    "moderationMode": settings.moderation_mode,
                    "moderationConfigured": moderation_configured,
                    "fallbackActive": fallback_active,
                }
            ),
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
