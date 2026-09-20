"""Deterministic names derived from XYP operation and endpoint names."""

from __future__ import annotations

import re

_OPERATION_PATTERN = re.compile(r"^(?P<code>[A-Z]{2}\d+)_(?P<name>.+)$")
_VERSION_SUFFIX = re.compile(r"-\d+(\.\d+)*$")
_WORD_BOUNDARY = re.compile(r"(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])")
_ENDPOINT_PATTERN = re.compile(r"xyp\.gov\.mn/(?P<endpoint>[^/]+)/ws")


def split_operation(operation: str) -> tuple[str, str]:
    """'WS100101_getCitizenIDCardInfo' -> ('WS100101', 'getCitizenIDCardInfo')."""
    match = _OPERATION_PATTERN.match(operation)
    if not match:
        raise ValueError(f"Unexpected operation name: {operation!r}")
    return match.group("code"), match.group("name")


def snake_case(name: str) -> str:
    """'getCitizenIDCardInfo' -> 'get_citizen_id_card_info'."""
    words = _WORD_BOUNDARY.sub("_", name).lower()
    return re.sub(r"_+", "_", words).strip("_")


def pascal_case(name: str) -> str:
    """'get_citizen_id_card_info' or 'getCitizenIDCardInfo' -> 'GetCitizenIdCardInfo'."""
    return "".join(word.capitalize() for word in snake_case(name).split("_"))


def endpoint_of(wsdl_url: str) -> str:
    """'https://xyp.gov.mn/citizen-1.5.0/ws?WSDL' -> 'citizen-1.5.0'."""
    match = _ENDPOINT_PATTERN.search(wsdl_url)
    if not match:
        raise ValueError(f"Unexpected endpoint URL: {wsdl_url!r}")
    return match.group("endpoint")


def group_of(endpoint: str) -> str:
    """'labor-welfare-1.5.0' -> 'labor_welfare'."""
    return _VERSION_SUFFIX.sub("", endpoint).replace("-", "_")
