from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field, model_validator


class RegionPatch(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    province_code: str = Field(alias="provinceCode")
    district_code: str = Field(alias="districtCode")
    sub_district_code: str | None = Field(None, alias="subDistrictCode")


class ProfilePatch(BaseModel):
    nickname: str | None = Field(None, min_length=1, max_length=30)
    bio: str | None = Field(None, max_length=500)
    region: RegionPatch | None = None

    @model_validator(mode="after")
    def at_least_one_field(self) -> "ProfilePatch":
        if not self.model_fields_set:
            raise ValueError("수정할 필드가 필요합니다.")
        return self


class InterestReplace(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    interest_ids: list[UUID] = Field(alias="interestIds", max_length=3)
