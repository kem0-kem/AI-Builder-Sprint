from collections import defaultdict, deque
from collections.abc import Callable
from time import monotonic

from fastapi import Request

from app.core.errors import ApiError

Buckets = dict[str, deque[float]]
_buckets: dict[str, Buckets] = defaultdict(dict)


def rate_limit(bucket: str, limit: int, window_seconds: int) -> Callable[[Request], None]:
    def enforce(request: Request) -> None:
        client = request.client.host if request.client else "unknown"
        key = f"{client}:{request.url.path}"
        now = monotonic()
        timestamps = _buckets[bucket].setdefault(key, deque())
        while timestamps and timestamps[0] <= now - window_seconds:
            timestamps.popleft()
        if len(timestamps) >= limit:
            raise ApiError("RATE_LIMITED", "요청 횟수 제한을 초과했습니다.", 429)
        timestamps.append(now)

    return enforce


login_limiter = rate_limit("login", 5, 60)
ai_limiter = rate_limit("ai", 30, 60)
report_limiter = rate_limit("report", 10, 3600)
