from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field


class MessageCreate(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    client_message_id: UUID = Field(alias="clientMessageId")
    content: str = Field(min_length=1, max_length=2000)


class ReadPositionUpdate(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    message_id: UUID = Field(alias="messageId")
