from pydantic import BaseModel, Field


class TextFeedbackRequest(BaseModel):
    content: str = Field(min_length=1, max_length=10000)


class FeedFeedbackRequest(TextFeedbackRequest):
    title: str = Field(min_length=1, max_length=120)
