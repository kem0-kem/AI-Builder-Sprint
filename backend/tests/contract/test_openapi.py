import json
from pathlib import Path

from app.main import create_app


def test_required_contract_and_removed_feed_ocr() -> None:
    paths = create_app().openapi()["paths"]
    required = {
        "/api/v1/auth/signup",
        "/api/v1/auth/check-username",
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


def test_username_availability_contract() -> None:
    operation = create_app().openapi()["paths"]["/api/v1/auth/check-username"]["get"]
    username = next(
        parameter for parameter in operation["parameters"] if parameter["name"] == "username"
    )

    assert username["required"] is True
    assert username["schema"]["minLength"] == 3
    assert username["schema"]["maxLength"] == 30
    assert username["schema"]["pattern"] == "^[a-z0-9_]+$"


def test_auth_success_responses_use_typed_envelopes_without_secret_examples() -> None:
    document = create_app().openapi()
    cases = (
        ("/api/v1/auth/signup", "201"),
        ("/api/v1/auth/login", "200"),
        ("/api/v1/auth/token/refresh", "200"),
    )
    for path, status in cases:
        schema = document["paths"][path]["post"]["responses"][status]["content"][
            "application/json"
        ]["schema"]
        assert "$ref" in schema
        component = document["components"]["schemas"][schema["$ref"].rsplit("/", 1)[-1]]
        assert {"ok", "data", "error", "meta"} <= set(component["properties"])

    serialized = json.dumps(document)
    assert "example-access-token" not in serialized
    assert "example-refresh-token" not in serialized


def test_readiness_documents_incomplete_configuration_response() -> None:
    responses = create_app().openapi()["paths"]["/api/v1/ready"]["get"]["responses"]

    assert responses["503"]["description"] == "Application dependencies are not ready"


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


def test_comment_chat_room_has_bodyless_typed_success_contract() -> None:
    document = create_app().openapi()
    operation = document["paths"]["/api/v1/comments/{comment_id}/chat-room"]["post"]
    response_schema = operation["responses"]["200"]["content"]["application/json"][
        "schema"
    ]

    assert "requestBody" not in operation
    assert "$ref" in response_schema

    response_component = document["components"]["schemas"][
        response_schema["$ref"].rsplit("/", 1)[-1]
    ]
    data_schema = response_component["properties"]["data"]
    assert "$ref" in data_schema

    data_component = document["components"]["schemas"][
        data_schema["$ref"].rsplit("/", 1)[-1]
    ]
    assert {"id", "type", "name"} <= set(data_component["properties"])
    assert data_component["properties"]["type"]["const"] == "DIRECT"
    assert data_component["properties"]["name"]["type"] == "null"


def test_openapi_snapshot_matches_application() -> None:
    snapshot = Path(__file__).parents[2] / "openapi" / "slowtalk-v1.json"
    assert json.loads(snapshot.read_text(encoding="utf-8")) == create_app().openapi()
