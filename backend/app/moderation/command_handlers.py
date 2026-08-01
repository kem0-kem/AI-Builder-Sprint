import inspect
from collections.abc import Awaitable, Callable
from uuid import UUID

from app.core.errors import ApiError

ModeratedCommandHandler = Callable[
    [dict[str, object], str], UUID | Awaitable[UUID]
]


class ModeratedCommandExecutionFailed(ApiError):
    def __init__(self) -> None:
        super().__init__(
            "MODERATED_COMMAND_EXECUTION_FAILED",
            "검토된 작업을 실행하지 못했습니다.",
            409,
        )


class ModeratedCommandRegistry:
    """Explicit operation allow-list; domain handlers are registered in later tasks."""

    def __init__(self) -> None:
        self._handlers: dict[str, ModeratedCommandHandler] = {}

    def register(self, operation: str, handler: ModeratedCommandHandler) -> None:
        if not operation or operation in self._handlers:
            raise ValueError("moderated operation is invalid or already registered")
        self._handlers[operation] = handler

    async def execute(
        self,
        operation: str,
        command: dict[str, object],
        idempotency_key: str | None = None,
    ) -> UUID:
        try:
            handler = self._handlers.get(operation)
            if handler is None:
                raise LookupError
            key = idempotency_key or _command_idempotency_key(command)
            result = handler(command, key)
            resource_id = await result if inspect.isawaitable(result) else result
            if not isinstance(resource_id, UUID):
                raise TypeError
            return resource_id
        except Exception:
            pass
        raise ModeratedCommandExecutionFailed() from None


def _command_idempotency_key(command: dict[str, object]) -> str:
    value = command.get("idempotencyKey")
    if not isinstance(value, str) or not value:
        raise ValueError
    return value
