from typing import Any

SENSITIVE_KEYS = {
    "accesstoken",
    "authorization",
    "email",
    "password",
    "refreshtoken",
    "token",
}


def redact_log_fields(payload: dict[str, Any]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in payload.items():
        normalized = key.replace("_", "").lower()
        if normalized in SENSITIVE_KEYS:
            result[key] = "[REDACTED]"
        elif isinstance(value, dict):
            result[key] = redact_log_fields(value)
        else:
            result[key] = value
    return result
