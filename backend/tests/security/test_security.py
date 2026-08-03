from app.core.redaction import redact_log_fields


def test_sensitive_values_are_redacted() -> None:
    payload = {"email": "user@example.com", "password": "secret", "accessToken": "jwt"}
    assert redact_log_fields(payload) == {
        "email": "[REDACTED]",
        "password": "[REDACTED]",
        "accessToken": "[REDACTED]",
    }
