from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field


class MeetingCreate(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    title: str = Field(min_length=1, max_length=80)
    description: str | None = Field(None, max_length=1000)
    invite_candidate_ids: list[UUID] = Field(alias="inviteCandidateIds", min_length=1, max_length=7)
