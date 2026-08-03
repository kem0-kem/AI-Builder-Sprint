from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field


class ReportAnalyzeRequest(BaseModel):
    content: str = Field(min_length=1, max_length=10000)


class ReportCreate(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    content: str = Field(min_length=1, max_length=10000)
    analysis_id: UUID = Field(alias="analysisId")
