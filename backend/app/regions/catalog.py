PROVINCES = [{"code": "11", "name": "서울특별시"}, {"code": "26", "name": "부산광역시"}]

DISTRICTS = {
    "11": [
        {"code": "11440", "name": "마포구"},
        {"code": "11680", "name": "강남구"},
    ],
    "26": [{"code": "26440", "name": "강서구"}],
}

SUB_DISTRICTS = {
    "11440": [{"code": "1144066000", "name": "합정동"}],
    "11680": [{"code": "1168058000", "name": "삼성동"}],
    "26440": [{"code": "2644053000", "name": "대저1동"}],
}


def find(items: list[dict[str, str]], code: str | None) -> dict[str, str] | None:
    return next((item for item in items if item["code"] == code), None)


def valid_region(province: str, district: str, sub_district: str | None) -> bool:
    if find(PROVINCES, province) is None or find(DISTRICTS.get(province, []), district) is None:
        return False
    return sub_district is None or find(SUB_DISTRICTS.get(district, []), sub_district) is not None
