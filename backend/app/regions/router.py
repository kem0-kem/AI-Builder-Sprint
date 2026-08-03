from fastapi import APIRouter

from app.common.responses import success
from app.core.errors import ApiError
from app.regions.catalog import DISTRICTS, PROVINCES, SUB_DISTRICTS

router = APIRouter(prefix="/regions", tags=["regions"])


@router.get("/provinces")
async def provinces() -> dict[str, object]:
    return success(PROVINCES)


@router.get("/provinces/{province_code}/districts")
async def districts(province_code: str) -> dict[str, object]:
    if province_code not in DISTRICTS:
        raise ApiError("RESOURCE_NOT_FOUND", "지역을 찾을 수 없습니다.", 404)
    return success(DISTRICTS[province_code])


@router.get("/districts/{district_code}/sub-districts")
async def sub_districts(district_code: str) -> dict[str, object]:
    if district_code not in SUB_DISTRICTS:
        raise ApiError("RESOURCE_NOT_FOUND", "지역을 찾을 수 없습니다.", 404)
    return success(SUB_DISTRICTS[district_code])
