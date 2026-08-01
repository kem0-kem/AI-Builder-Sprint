from app.main import create_app


def test_required_contract_and_removed_feed_ocr() -> None:
    paths = create_app().openapi()["paths"]
    required = {
        "/api/v1/auth/signup",
        "/api/v1/users/me",
        "/api/v1/letters",
        "/api/v1/chat-rooms",
        "/api/v1/meetings",
        "/api/v1/feeds",
        "/api/v1/feeds/feedback",
        "/api/v1/reports/feedback",
    }
    assert required <= set(paths)
    assert "/api/v1/feeds/ocr" not in paths
