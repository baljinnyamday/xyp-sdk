"""Base class and field types shared by every generated response model."""

from __future__ import annotations

import warnings
from dataclasses import dataclass, field
from datetime import datetime
from functools import cache
from types import UnionType
from typing import Annotated, Any, TypeVar, Union, cast, get_args, get_origin

from pydantic import (
    BaseModel,
    BeforeValidator,
    ConfigDict,
    PrivateAttr,
    ValidationError,
    model_validator,
)

from xyp.errors import XypValidationError

ModelT = TypeVar("ModelT", bound="XypModel")


def _parse_date(value: Any) -> Any:
    """ISO 8601 text -> datetime. Anything else is kept as text rather than rejected:
    providers fill XYP date fields by hand and formats are not documented."""
    if not isinstance(value, str):
        return value
    text = value.strip()
    iso = f"{text[:-1]}+00:00" if text.endswith(("Z", "z")) else text
    try:
        return datetime.fromisoformat(iso)
    except ValueError:
        return text


XypDate = Annotated[datetime | str, BeforeValidator(_parse_date)]


def _is_list(annotation: Any) -> bool:
    if get_origin(annotation) is list:
        return True
    if get_origin(annotation) in (Union, UnionType):
        return any(_is_list(option) for option in get_args(annotation))
    return False


@cache
def _list_aliases(model: type[BaseModel]) -> frozenset[str]:
    return frozenset(
        field.alias or name
        for name, field in model.model_fields.items()
        if _is_list(field.annotation)
    )


ISSUES_URL = "https://github.com/baljinnyamday/xyp-sdk/issues"
_WARNING_STACK_LEVEL = 4  # parse_model <- generated operation <- the caller's line


class XypModelMismatchWarning(UserWarning):
    """XYP's data did not fit a generated model; the call still succeeded."""


@dataclass(frozen=True)
class Mismatch:
    """One field of a response that did not fit the SDK's model."""

    path: str  # e.g. "listData[1].year"; "" is the response itself
    problem: str
    value: Any = field(repr=False)  # raw value from XYP; kept out of repr, it is citizen data


def _as_list(value: Any) -> list[Any]:
    return cast("list[Any]", value) if isinstance(value, list) else [value]


def _list_items(value: Any) -> list[Any]:
    """A single item stands for a one-item list. Empty/nil items carry no data and
    would fail the whole list, so they are dropped."""
    return [item for item in _as_list(value) if item is not None]


class XypModel(BaseModel):
    """Response data. Every field is optional and unknown fields are kept, so a
    provider adding or omitting a field never breaks your code."""

    model_config = ConfigDict(extra="allow", populate_by_name=True, frozen=True)

    _xyp_mismatches: tuple[Mismatch, ...] = PrivateAttr(default=())

    @property
    def xyp_mismatches(self) -> tuple[Mismatch, ...]:
        """Fields XYP sent that did not fit this model (normally empty). Each entry has
        the field `path`, the `problem` and the raw `value`; the field itself is None."""
        return self._xyp_mismatches

    @model_validator(mode="before")
    @classmethod
    def _wrap_single_items(cls, data: Any) -> Any:
        """XML cannot tell a one-item list from a single value; the field type can."""
        if not isinstance(data, dict):
            return data
        raw = cast("dict[str, Any]", data)
        aliases = _list_aliases(cls)
        return {
            key: _list_items(value) if key in aliases and value is not None else value
            for key, value in raw.items()
        }


def parse_model(model: type[ModelT], data: Any) -> ModelT:
    """Turn a parsed `<response>` into its model without ever losing a successful call.

    The models are generated from XYP's public catalog, which is typed by hand, so
    real data can disagree with them. A field that does not fit is set to `None`,
    its raw value is kept on `model.xyp_mismatches`, and an `XypModelMismatchWarning`
    says which field it was and that the SDK's model (not XYP, not the caller) is wrong.
    """
    if data is None:
        return model()
    if not isinstance(data, dict):
        return _with_mismatches(model(), (Mismatch("", "expected an object", data),))
    raw = cast("dict[str, Any]", data)
    try:
        return model.model_validate(raw)
    except ValidationError as error:
        issues = {
            _data_path(raw, issue["loc"]): issue["msg"]
            for issue in error.errors(include_input=False)
        }
    mismatches = tuple(
        Mismatch(_path_text(path), problem, _value_at(raw, path))
        for path, problem in issues.items()
    )
    repaired = raw
    for path in issues:
        repaired = _without(repaired, _repair_path(path))
    try:
        return _with_mismatches(model.model_validate(repaired), mismatches)
    except ValidationError:
        raise XypValidationError(
            f"{model.__name__} could not be built from XYP's response even after dropping "
            f"the fields that do not fit ({_describe(mismatches)}). The call itself "
            "succeeded: the parsed response is on this error's `.data`. This is a gap in "
            "the SDK's models, not an error from XYP or in your code; please report it.",
            raw,
        ) from None


def _with_mismatches(model: ModelT, mismatches: tuple[Mismatch, ...]) -> ModelT:
    model._xyp_mismatches = mismatches  # pyright: ignore[reportPrivateUsage]
    warnings.warn(
        f"{type(model).__name__}: XYP's response does not fit the SDK's model at "
        f"{_describe(mismatches)}. The call succeeded and nothing was lost: those fields "
        "are None and their raw values are in `.xyp_mismatches`. This is a gap in the "
        "SDK's models (generated from XYP's public catalog), not an error from XYP or in "
        f"your code. Please report it at {ISSUES_URL} so the model can be fixed.",
        XypModelMismatchWarning,
        stacklevel=_WARNING_STACK_LEVEL,
    )
    return model


def _describe(mismatches: tuple[Mismatch, ...]) -> str:
    """Field paths and reasons only. Values are citizen data and stay out of logs."""
    return "; ".join(f"{item.path or '<response>'} ({item.problem})" for item in mismatches)


def _path_text(loc: tuple[int | str, ...]) -> str:
    return "".join(f"[{part}]" if isinstance(part, int) else f".{part}" for part in loc).lstrip(".")


def _repair_path(loc: tuple[int | str, ...]) -> tuple[int | str, ...]:
    """Where to put the `None`: the field itself, or the whole list when one of its
    items is not an object at all (a list cannot hold `None` items)."""
    return loc[:-1] if isinstance(loc[-1], int) else loc


def _step(node: Any, part: int | str) -> Any:
    """One step down the parsed XML. A single item stands for a one-item list."""
    if isinstance(part, int):
        return cast("list[Any]", node)[part] if isinstance(node, list) else node
    return cast("dict[str, Any]", node).get(part) if isinstance(node, dict) else None


def _data_path(data: Any, loc: tuple[int | str, ...]) -> tuple[int | str, ...]:
    """The part of a pydantic error location that exists in the data. Locations of
    union fields end in the branch that was tried (`birthDate.datetime`), which is
    not a place in the response."""
    path: tuple[int | str, ...] = ()
    node = data
    for part in loc:
        is_key = isinstance(part, str) and isinstance(node, dict) and part in node
        if not (is_key or isinstance(part, int)):
            break
        path = (*path, part)
        node = _step(node, part)
    return path


def _value_at(data: Any, loc: tuple[int | str, ...]) -> Any:
    node = data
    for part in loc:
        node = _step(node, part)
    return node


def _without(node: Any, loc: tuple[int | str, ...]) -> Any:
    """A copy of `node` with the value at `loc` replaced by None."""
    if not loc:
        return None
    head, rest = loc[0], loc[1:]
    if isinstance(head, int):
        if not isinstance(node, list):
            return _without(node, rest)
        items = cast("list[Any]", node)
        return [_without(item, rest) if index == head else item for index, item in enumerate(items)]
    if not isinstance(node, dict):
        return node
    fields = cast("dict[str, Any]", node)
    return {key: _without(value, rest) if key == head else value for key, value in fields.items()}
