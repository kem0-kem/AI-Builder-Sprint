from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field


class FeedCreate(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    category_id: UUID = Field(alias="categoryId")
    title: str = Field(min_length=1, max_length=120)
    content: str = Field(min_length=1, max_length=10000)


class FeedPatch(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    category_id: UUID | None = Field(None, alias="categoryId")
    title: str | None = Field(None, min_length=1, max_length=120)
    content: str | None = Field(None, min_length=1, max_length=10000)


class CommentCreate(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    content: str = Field(min_length=1, max_length=2000)
    parent_comment_id: UUID | None = Field(None, alias="parentCommentId")


class CommentPatch(BaseModel):
    content: str = Field(min_length=1, max_length=2000)


class ReportCreate(BaseModel):
    reason: str = Field(min_length=1, max_length=500)
