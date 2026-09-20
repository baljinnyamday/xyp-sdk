from __future__ import annotations

from datetime import datetime, timezone

import pytest
from pydantic import Base64Bytes, Field

from xyp import (
    NotFoundError,
    XypApiError,
    XypConfigError,
    XypConnectionError,
    XypModel,
    XypModelMismatchWarning,
    XypResponseError,
    XypTimeoutError,
    XypValidationError,
)
from xyp._models import XypDate, parse_model


class Item(XypModel):
    year: int | None = None


class Sample(XypModel):
    first_name: str | None = Field(default=None, alias="firstName")
    age: int | None = None
    active: bool | None = None
    born: XypDate | None = None
    photo: Base64Bytes | None = None
    items: list[Item] | None = Field(default=None, alias="listData")


def test_text_is_coerced_by_field_type() -> None:
    sample = parse_model(
        Sample,
        {"firstName": "Бат", "age": "34", "active": "true", "photo": "AAE=", "born": "1990-05-01"},
    )

    assert sample.first_name == "Бат"
    assert sample.age == 34
    assert sample.active is True
    assert sample.photo == b"\x00\x01"
    assert sample.born == datetime(1990, 5, 1)


@pytest.mark.parametrize(
    ("text", "expected"),
    [
        ("2024-01-31T12:00:00+08:00", datetime.fromisoformat("2024-01-31T12:00:00+08:00")),
        ("2024-01-31T12:00:00Z", datetime(2024, 1, 31, 12, 0, tzinfo=timezone.utc)),
        ("2020", "2020"),  # must not be read as a unix timestamp
        ("31.01.2024", "31.01.2024"),  # undocumented formats stay text instead of failing
    ],
)
def test_dates_are_lenient(text: str, expected: object) -> None:
    assert parse_model(Sample, {"born": text}).born == expected


def test_single_item_becomes_a_list() -> None:
    assert parse_model(Sample, {"listData": {"year": "2020"}}).items == [Item(year=2020)]
    assert parse_model(Sample, {"listData": [{"year": "1"}, {"year": "2"}]}).items == [
        Item(year=1),
        Item(year=2),
    ]
    assert parse_model(Sample, {"listData": None}).items is None


def test_missing_response_gives_an_empty_model_and_unknown_fields_are_kept() -> None:
    assert parse_model(Sample, None) == Sample()
    assert parse_model(Sample, {"brandNew": "x"}).model_extra == {"brandNew": "x"}


def test_a_field_that_does_not_fit_never_fails_the_call() -> None:
    data = {"firstName": "Бат", "age": "not-a-number-РД00000000"}

    with pytest.warns(XypModelMismatchWarning, match=r"age .*not an error from XYP") as caught:
        sample = parse_model(Sample, data)

    assert sample.first_name == "Бат"  # everything that fits is still typed
    assert sample.age is None
    assert [(m.path, m.value) for m in sample.xyp_mismatches] == [("age", data["age"])]
    # citizen data must not reach logs through the warning or a repr
    assert "РД00000000" not in str(caught[0].message)
    assert "РД00000000" not in repr(sample.xyp_mismatches)


def test_mismatches_inside_lists_only_drop_what_is_wrong() -> None:
    data = {"listData": [{"year": "2020"}, {"year": "MMXXI"}]}
    with pytest.warns(XypModelMismatchWarning, match=r"listData\[1\]\.year"):
        sample = parse_model(Sample, data)
    assert sample.items == [Item(year=2020), Item(year=None)]
    assert sample.xyp_mismatches[0].value == "MMXXI"

    # a single item (XML cannot mark it as a list) is addressed the same way
    with pytest.warns(XypModelMismatchWarning, match=r"listData\[0\]\.year"):
        single = parse_model(Sample, {"listData": {"year": "?"}})
    assert single.items == [Item(year=None)]

    # an item that is not an object at all: the list is dropped, its raw value kept
    with pytest.warns(XypModelMismatchWarning):
        broken = parse_model(Sample, {"age": "1", "listData": ["text", {"year": "1"}]})
    assert broken.age == 1
    assert broken.items is None
    assert broken.xyp_mismatches[0].value == "text"


def test_nil_items_do_not_cost_the_rest_of_the_list() -> None:
    sample = parse_model(Sample, {"listData": [None, {"year": "2020"}]})
    assert sample.items == [Item(year=2020)]
    assert sample.xyp_mismatches == ()


def test_a_response_that_is_not_an_object_still_returns() -> None:
    with pytest.warns(XypModelMismatchWarning, match="<response>"):
        sample = parse_model(Sample, "just text")
    assert sample.model_dump() == Sample().model_dump()
    assert sample.xyp_mismatches[0].value == "just text"


def test_clean_responses_have_no_mismatches() -> None:
    assert parse_model(Sample, {"age": "1"}).xyp_mismatches == ()


def test_every_error_says_whose_side_it_is_on() -> None:
    assert XypConfigError.origin == "config"
    assert XypConnectionError.origin == XypTimeoutError.origin == "network"
    assert XypResponseError.origin == XypApiError.origin == NotFoundError.origin == "xyp"
    assert XypValidationError.origin == "sdk"
