from typing import Any, Literal
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field


class MessageCreate(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    client_message_id: UUID = Field(alias="clientMessageId")
    content: str = Field(min_length=1, max_length=2000)


class ReadPositionUpdate(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    message_id: UUID = Field(alias="messageId")


class ChatRoomReadUpdate(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    last_read_message_id: UUID = Field(alias="lastReadMessageId")


class ChatRoomView(BaseModel):
    id: UUID
    type: Literal["DIRECT"]
    name: None = None


class ChatRoomSuccessResponse(BaseModel):
    ok: Literal[True] = True
    data: ChatRoomView
    error: None = None
    meta: dict[str, Any] | None = None
