import base64
import binascii
import hashlib
import json
import math
import os
import unicodedata
from typing import Any, cast

from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from pydantic import BaseModel, ConfigDict, Field

AAD = b"slowtalk-moderation-v1"
NONCE_BYTES = 12


class ModerationCryptoError(ValueError):
    def __init__(self) -> None:
        super().__init__("moderation payload processing failed")


class EncryptedPayload(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    ciphertext: str = Field(repr=False)
    nonce: str = Field(repr=False)


def _decode_base64(value: str, *, field: str) -> bytes:
    try:
        decoded = base64.b64decode(value, validate=True)
    except (binascii.Error, ValueError) as exc:
        raise ValueError(f"{field} must be strict base64") from exc
    if base64.b64encode(decoded).decode("ascii") != value:
        raise ValueError(f"{field} must be canonical base64")
    return decoded


class CommandCipher:
    def __init__(self, encryption_key: str) -> None:
        key = _decode_base64(encryption_key, field="encryption key")
        if len(key) != 32:
            raise ValueError("encryption key must decode to exactly 32 bytes")
        self._aesgcm = AESGCM(key)

    def encrypt(self, payload: dict[str, object]) -> EncryptedPayload:
        try:
            _validate_json_value(payload, seen=set())
            plaintext = json.dumps(
                payload,
                allow_nan=False,
                ensure_ascii=False,
                sort_keys=True,
                separators=(",", ":"),
            ).encode("utf-8")
        except (RecursionError, TypeError, UnicodeError, ValueError):
            raise ModerationCryptoError() from None
        nonce = os.urandom(NONCE_BYTES)
        ciphertext = self._aesgcm.encrypt(nonce, plaintext, AAD)
        return EncryptedPayload(
            ciphertext=base64.b64encode(ciphertext).decode("ascii"),
            nonce=base64.b64encode(nonce).decode("ascii"),
        )

    def decrypt(self, payload: EncryptedPayload) -> dict[str, object]:
        try:
            ciphertext = _decode_base64(payload.ciphertext, field="ciphertext")
            nonce = _decode_base64(payload.nonce, field="nonce")
            if len(nonce) != NONCE_BYTES:
                raise ModerationCryptoError()
        except (ModerationCryptoError, ValueError):
            raise ModerationCryptoError() from None
        plaintext = self._aesgcm.decrypt(nonce, ciphertext, AAD)
        try:
            decoded: Any = json.loads(
                plaintext.decode("utf-8"),
                object_pairs_hook=_object_from_pairs,
                parse_constant=_reject_json_constant,
            )
            if not isinstance(decoded, dict):
                raise ModerationCryptoError()
            _validate_json_value(decoded, seen=set())
        except (RecursionError, TypeError, UnicodeError, ValueError):
            raise ModerationCryptoError() from None
        return cast(dict[str, object], decoded)


def normalize_content(text: str) -> str:
    normalized_newlines = text.replace("\r\n", "\n").replace("\r", "\n")
    return unicodedata.normalize("NFC", normalized_newlines).strip()


def content_hash(text: str, pepper: str) -> str:
    normalized = normalize_content(text)
    try:
        encoded = f"{pepper}:{normalized}".encode()
    except UnicodeEncodeError:
        raise ModerationCryptoError() from None
    return hashlib.sha256(encoded).hexdigest()


def _validate_json_value(value: object, *, seen: set[int]) -> None:
    value_type = type(value)
    if value is None or value_type is bool or value_type is int:
        return
    if value_type is str:
        assert isinstance(value, str)
        value.encode("utf-8")
        return
    if value_type is float:
        assert isinstance(value, float)
        if not math.isfinite(value):
            raise ModerationCryptoError()
        return
    if value_type is list:
        assert isinstance(value, list)
        identity = id(value)
        if identity in seen:
            raise ModerationCryptoError()
        seen.add(identity)
        try:
            for item in value:
                _validate_json_value(item, seen=seen)
        finally:
            seen.remove(identity)
        return
    if value_type is dict:
        assert isinstance(value, dict)
        identity = id(value)
        if identity in seen:
            raise ModerationCryptoError()
        seen.add(identity)
        try:
            for key, item in value.items():
                if type(key) is not str:
                    raise ModerationCryptoError()
                assert isinstance(key, str)
                key.encode("utf-8")
                _validate_json_value(item, seen=seen)
        finally:
            seen.remove(identity)
        return
    raise ModerationCryptoError()


def _object_from_pairs(pairs: list[tuple[str, object]]) -> dict[str, object]:
    result: dict[str, object] = {}
    for key, value in pairs:
        if key in result:
            raise ModerationCryptoError()
        result[key] = value
    return result


def _reject_json_constant(_value: str) -> object:
    raise ModerationCryptoError()
