from pydantic import BaseModel, Field


class LetterCreate(BaseModel):
    content: str = Field(min_length=1, max_length=5000)
    match: bool = False
