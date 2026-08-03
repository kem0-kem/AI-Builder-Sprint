from typing import Any


def success(data: Any, *, meta: Any = None) -> dict[str, Any]:
    return {"ok": True, "data": data, "error": None, "meta": meta}


def page(data: Any, *, next_cursor: str | None) -> dict[str, Any]:
    return success(data, meta={"nextCursor": next_cursor, "hasNext": next_cursor is not None})
