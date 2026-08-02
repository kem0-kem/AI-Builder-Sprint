import json
from pathlib import Path

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
        "/api/v1/letters/ocr",
        "/api/v1/reports/ocr",
        "/api/v1/moderation-submissions/{submission_id}",
        "/api/v1/ready",
    }
    assert required <= set(paths)
    assert "/api/v1/feeds/ocr" not in paths


def test_readiness_documents_incomplete_configuration_response() -> None:
    responses = create_app().openapi()["paths"]["/api/v1/ready"]["get"]["responses"]

    assert responses["503"]["description"] == (
        "Moderation configuration is incomplete"
    )


def test_moderated_writes_document_async_and_blocked_responses() -> None:
    paths = create_app().openapi()["paths"]
    moderated = (
        ("/api/v1/letters", "post"),
        ("/api/v1/chat-rooms/{room_id}/messages", "post"),
        ("/api/v1/feeds", "post"),
        ("/api/v1/feeds/{feed_id}", "patch"),
        ("/api/v1/feeds/{feed_id}/comments", "post"),
        ("/api/v1/comments/{comment_id}", "patch"),
        ("/api/v1/reports", "post"),
        ("/api/v1/letters/ocr", "post"),
        ("/api/v1/reports/ocr", "post"),
    )
    for path, method in moderated:
        responses = paths[path][method]["responses"]
        assert {"202", "422"} <= set(responses)


def test_openapi_snapshot_matches_application() -> None:
    snapshot = Path(__file__).parents[2] / "openapi" / "slowtalk-v1.json"
    assert json.loads(snapshot.read_text(encoding="utf-8")) == create_app().openapi()
