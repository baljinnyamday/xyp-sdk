"""Go-specific identifier rules."""

from __future__ import annotations

# A Go identifier cannot start with a digit; clean_name already rejects wire names
# that do, so this prefix is a guard rather than something the catalog needs today.
_DIGIT_PREFIX = "X"


def upper_first(text: str) -> str:
    return text[:1].upper() + text[1:]


def go_field_name(wire_name: str) -> str:
    """'created_date' -> 'CreatedDate', 'civilId' -> 'CivilId'.

    XYP's own spelling is kept inside each word: applying Go's initialism rules
    (Id -> ID, Url -> URL) would make the Go name stop resembling the wire name
    the `xyp` tag carries, for no gain to the reader.
    """
    parts = [part for part in wire_name.split("_") if part]
    name = "".join(upper_first(part) for part in parts)
    if not name or name[0].isdigit():
        name = f"{_DIGIT_PREFIX}{name}"
    return name


def go_package_name(group: str) -> str:
    """'labor_welfare' -> 'laborwelfare'. Go package names hold no underscores."""
    return group.replace("_", "")
