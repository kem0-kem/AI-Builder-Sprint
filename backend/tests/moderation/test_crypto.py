import base64
import json
import math

import pytest
from cryptography.exceptions import InvalidTag
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

from app.moderation.crypto import (
    AAD,
    CommandCipher,
    EncryptedPayload,
    ModerationCryptoError,
    content_hash,
    normalize_content,
)


@pytest.fixture
def cipher() -> CommandCipher:
    return CommandCipher(base64.b64encode(b"k" * 32).decode("ascii"))


def test_cipher_round_trip_and_random_nonce(cipher: CommandCipher) -> None:
    command = {"content": "격리할 글", "match": True}

    first = cipher.encrypt(command)
    second = cipher.encrypt(command)

    assert cipher.decrypt(first) == command
    assert first.nonce != second.nonce
    assert "격리할 글" not in first.ciphertext


def test_cipher_rejects_modified_ciphertext(cipher: CommandCipher) -> None:
    encrypted = cipher.encrypt({"content": "격리할 글", "match": True})
    raw = bytearray(base64.b64decode(encrypted.ciphertext, validate=True))
    raw[0] ^= 1
    modified = encrypted.model_copy(
        update={"ciphertext": base64.b64encode(raw).decode("ascii")}
    )

    with pytest.raises(InvalidTag):
        cipher.decrypt(modified)


def test_cipher_rejects_modified_nonce(cipher: CommandCipher) -> None:
    encrypted = cipher.encrypt({"content": "격리할 글"})
    raw = bytearray(base64.b64decode(encrypted.nonce, validate=True))
    raw[-1] ^= 1
    modified = encrypted.model_copy(update={"nonce": base64.b64encode(raw).decode("ascii")})

    with pytest.raises(InvalidTag):
        cipher.decrypt(modified)


@pytest.mark.parametrize("field", ["ciphertext", "nonce"])
def test_cipher_rejects_non_strict_base64(cipher: CommandCipher, field: str) -> None:
    encrypted = cipher.encrypt({"content": "safe"})
    modified = encrypted.model_copy(update={field: "!!!!"})

    with pytest.raises(ModerationCryptoError, match="moderation payload processing failed"):
        cipher.decrypt(modified)


def test_cipher_rejects_wrong_nonce_length(cipher: CommandCipher) -> None:
    encrypted = cipher.encrypt({"content": "safe"})
    modified = encrypted.model_copy(
        update={"nonce": base64.b64encode(b"short").decode("ascii")}
    )

    with pytest.raises(ModerationCryptoError, match="moderation payload processing failed"):
        cipher.decrypt(modified)


def test_content_hash_normalizes_nfc_newlines_and_outer_whitespace() -> None:
    decomposed = "  cafe\u0301\r\nsecond\rline  "
    normalized = "café\nsecond\nline"

    assert normalize_content(decomposed) == normalized
    assert content_hash(decomposed, "pepper") == content_hash(normalized, "pepper")
    assert content_hash(normalized, "pepper") != content_hash(normalized, "other-pepper")


def test_cipher_rejects_invalid_key_material() -> None:
    with pytest.raises(ValueError, match="base64"):
        CommandCipher("not base64!")
    with pytest.raises(ValueError, match="32 bytes"):
        CommandCipher(base64.b64encode(b"short").decode("ascii"))


def assert_safe_crypto_error(
    caught: pytest.ExceptionInfo[ModerationCryptoError], marker: str
) -> None:
    assert str(caught.value) == "moderation payload processing failed"
    assert marker not in str(caught.value)
    assert marker not in repr(caught.value)
    assert caught.value.__cause__ is None


def test_encrypt_hides_lone_surrogate_payload_from_error() -> None:
    marker = "PAYLOAD_MARKER"
    cipher = CommandCipher(base64.b64encode(b"k" * 32).decode("ascii"))

    with pytest.raises(ModerationCryptoError) as caught:
        cipher.encrypt({"content": marker + "\ud800"})

    assert_safe_crypto_error(caught, marker)


def test_encrypt_rejects_recursive_payload_without_marker_leak() -> None:
    marker = "CYCLE_MARKER"
    payload: dict[str, object] = {"content": marker}
    payload["self"] = payload
    cipher = CommandCipher(base64.b64encode(b"k" * 32).decode("ascii"))

    with pytest.raises(ModerationCryptoError) as caught:
        cipher.encrypt(payload)

    assert_safe_crypto_error(caught, marker)


def test_content_hash_hides_text_and_pepper_from_encoding_error() -> None:
    text_marker = "TEXT_MARKER"
    pepper_marker = "PEPPER_MARKER"

    with pytest.raises(ModerationCryptoError) as text_error:
        content_hash(text_marker + "\ud800", "pepper")
    with pytest.raises(ModerationCryptoError) as pepper_error:
        content_hash("content", pepper_marker + "\ud800")

    assert_safe_crypto_error(text_error, text_marker)
    assert_safe_crypto_error(pepper_error, pepper_marker)


@pytest.mark.parametrize(
    "payload",
    [
        {1: "value"},
        {"items": ("tuple",)},
        {"items": {"set"}},
        {"number": math.nan},
        {"number": math.inf},
        {"number": -math.inf},
    ],
)
def test_encrypt_rejects_non_standard_json_without_coercion(payload: dict[object, object]) -> None:
    cipher = CommandCipher(base64.b64encode(b"k" * 32).decode("ascii"))

    with pytest.raises(ModerationCryptoError) as caught:
        cipher.encrypt(payload)  # type: ignore[arg-type]

    assert str(caught.value) == "moderation payload processing failed"


@pytest.mark.parametrize(
    "raw",
    [
        b"\xff",
        b"not-json",
        json.dumps(["not", "an", "object"]).encode(),
        b'{"number":NaN}',
        b'{"content":"DECRYPT_MARKER\\ud800"}',
        b'{"content":"DECRYPT_MARKER","duplicate":1,"duplicate":2}',
    ],
)
def test_decrypt_rejects_invalid_or_non_standard_json_safely(raw: bytes) -> None:
    key = b"k" * 32
    nonce = b"n" * 12
    encrypted = EncryptedPayload(
        ciphertext=base64.b64encode(AESGCM(key).encrypt(nonce, raw, AAD)).decode("ascii"),
        nonce=base64.b64encode(nonce).decode("ascii"),
    )
    cipher = CommandCipher(base64.b64encode(key).decode("ascii"))

    with pytest.raises(ModerationCryptoError) as caught:
        cipher.decrypt(encrypted)

    assert_safe_crypto_error(caught, "DECRYPT_MARKER")


def test_encrypted_payload_repr_hides_material() -> None:
    encrypted = EncryptedPayload(ciphertext="CIPHERTEXT_MARKER", nonce="NONCE_MARKER")

    rendered = repr(encrypted)
    assert "CIPHERTEXT_MARKER" not in rendered
    assert "NONCE_MARKER" not in rendered
