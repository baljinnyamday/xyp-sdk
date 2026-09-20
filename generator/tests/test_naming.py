from __future__ import annotations

import pytest

from xyp_generator.naming import endpoint_of, group_of, pascal_case, snake_case, split_operation
from xyp_generator.python.names import python_name, unique_names


def test_operation_names() -> None:
    assert split_operation("WS100101_getCitizenIDCardInfo") == ("WS100101", "getCitizenIDCardInfo")
    assert split_operation("GS10002_citizenCovid19EPass") == ("GS10002", "citizenCovid19EPass")
    with pytest.raises(ValueError, match="Unexpected"):
        split_operation("getSomething")


@pytest.mark.parametrize(
    ("name", "expected"),
    [
        ("getCitizenIDCardInfo", "get_citizen_id_card_info"),
        ("eHealthGetHospitals", "e_health_get_hospitals"),
        ("ForeignCitizenInfoService", "foreign_citizen_info_service"),
        ("citizenCovid19EPass", "citizen_covid19_e_pass"),
        ("ttv6_2_1", "ttv6_2_1"),
        ("civilId", "civil_id"),
    ],
)
def test_snake_case(name: str, expected: str) -> None:
    assert snake_case(name) == expected


def test_pascal_case_and_groups() -> None:
    assert pascal_case("getCitizenIDCardInfo") == "GetCitizenIdCardInfo"
    assert endpoint_of("https://xyp.gov.mn/labor-welfare-1.5.0/ws?WSDL") == "labor-welfare-1.5.0"
    assert group_of("labor-welfare-1.5.0") == "labor_welfare"
    assert group_of("education-1.3.0") == group_of("education-1.5.0") == "education"


def test_python_names_avoid_keywords_builtins_and_pydantic_attributes() -> None:
    assert python_name("from") == "from_"
    assert python_name("list") == "list_"
    assert python_name("register") == "register_"
    assert python_name("modelName") == "model_name_"
    assert python_name("auth", reserved=("auth",)) == "auth_"
    assert python_name("8ball") == "field_8ball"
    assert unique_names(["lastName", "lastname", "last_name"]) == {
        "lastName": "last_name",
        "lastname": "lastname",
        "last_name": "last_name_2",
    }
