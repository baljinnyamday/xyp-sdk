"""Render the TypeScript SDK's generated modules from the IR."""

from __future__ import annotations

import json
import shutil
import subprocess
from dataclasses import dataclass
from pathlib import Path

from jinja2 import Environment, PackageLoader, StrictUndefined

from xyp_generator.ir import Api, Field, Service, TypeRef
from xyp_generator.naming import split_operation

_SCALARS = {
    "string": "string",
    "int": "number",
    "float": "number",
    "bool": "boolean",
    "decimal": "string",  # no decimal type in JS; text keeps the precision
    "bytes": "Uint8Array",
    "date": "Date | string",
    "any": "unknown",
}
_INPUT_OBJECT = "Readonly<Record<string, unknown>>"
# Names that cannot be used for a function declaration.
_RESERVED = frozenset(
    {
        "break",
        "case",
        "catch",
        "class",
        "const",
        "continue",
        "debugger",
        "default",
        "delete",
        "do",
        "else",
        "enum",
        "export",
        "extends",
        "false",
        "finally",
        "for",
        "function",
        "if",
        "import",
        "in",
        "instanceof",
        "new",
        "null",
        "return",
        "super",
        "switch",
        "this",
        "throw",
        "true",
        "try",
        "typeof",
        "var",
        "void",
        "while",
        "with",
        "yield",
        "let",
        "static",
        "implements",
        "interface",
        "package",
        "private",
        "protected",
        "public",
        "await",
        "async",
        "arguments",
        "eval",
    }
)
_GENERATED_DIRS = ("operations", "services", "types")


@dataclass(frozen=True)
class PropertyView:
    name: str
    type: str
    doc: str


@dataclass(frozen=True)
class InterfaceView:
    name: str
    doc: str
    properties: tuple[PropertyView, ...]


@dataclass(frozen=True)
class OperationView:
    name: str  # function, method and module name
    operation: str
    doc: str
    organization: str
    params: InterfaceView
    interfaces: tuple[InterfaceView, ...]  # nested first, the response last
    response: str | None
    schema: str  # TypeScript literal
    accepts_auth: bool

    @property
    def exported_types(self) -> tuple[str, ...]:
        return tuple(sorted([self.params.name, *(item.name for item in self.interfaces)]))


@dataclass(frozen=True)
class GroupView:
    name: str  # "laborWelfare"
    class_name: str  # "LaborWelfare"
    endpoints: tuple[str, ...]
    operations: tuple[OperationView, ...]


def _upper_first(text: str) -> str:
    return text[:1].upper() + text[1:]


def _lower_first(text: str) -> str:
    return text[:1].lower() + text[1:]


def _camel(group: str) -> str:
    head, *rest = group.split("_")
    return head + "".join(_upper_first(part) for part in rest)


def _doc(text: str) -> str:
    return " ".join(text.replace("*/", "* /").split())


def _wrap_list(item: str) -> str:
    return f"readonly ({item})[]" if " " in item else f"readonly {item}[]"


def _output_type(type_ref: TypeRef, interface: str) -> str:
    if type_ref.kind == "list" and type_ref.item:
        return _wrap_list(_output_type(type_ref.item, interface))
    return interface if type_ref.kind == "object" else _SCALARS[type_ref.kind]


def _input_type(type_ref: TypeRef) -> str:
    if type_ref.kind == "list" and type_ref.item:
        return _wrap_list(_input_type(type_ref.item))
    return _INPUT_OBJECT if type_ref.kind == "object" else _SCALARS[type_ref.kind]


def _field_spec(field: Field, type_ref: TypeRef) -> str:
    if type_ref.kind == "list" and type_ref.item:
        return f"{{ list: {_field_spec(field, type_ref.item)} }}"
    if type_ref.kind == "object":
        return f"{{ object: {_schema(field.children)} }}"
    return json.dumps(type_ref.kind)


def _schema(fields: tuple[Field, ...]) -> str:
    entries = ", ".join(f"{field.wire_name}: {_field_spec(field, field.type)}" for field in fields)
    return f"{{ {entries} }}" if entries else "{}"


def _interfaces_for(name: str, doc: str, fields: tuple[Field, ...]) -> tuple[InterfaceView, ...]:
    """Depth-first, nested interfaces before the ones that use them."""
    nested: tuple[InterfaceView, ...] = ()
    properties: tuple[PropertyView, ...] = ()
    for field in fields:
        child = f"{name}{_upper_first(field.wire_name)}"
        if field.children:
            nested = (*nested, *_interfaces_for(child, field.doc, field.children))
        field_type = f"{_output_type(field.type, child)} | null"
        properties = (*properties, PropertyView(field.wire_name, field_type, _doc(field.doc)))
    return (*nested, InterfaceView(name, _doc(doc), properties))


def _operation_view(service: Service, name: str, base: str) -> OperationView:
    summary = _doc(f"{service.operation}: {service.doc}")
    interfaces: tuple[InterfaceView, ...] = ()
    if service.outputs:
        *nested, root = _interfaces_for(base, "", service.outputs)
        interfaces = (*nested, InterfaceView(f"{base}Response", summary, root.properties))
    params = InterfaceView(
        f"{base}Params",
        f"Input of {service.operation}.",
        tuple(
            PropertyView(field.wire_name, _input_type(field.type), _doc(field.doc))
            for field in service.inputs
        ),
    )
    return OperationView(
        name=name,
        operation=service.operation,
        doc=_doc(service.doc),
        organization=_doc(service.organization),
        params=params,
        interfaces=interfaces,
        response=f"{base}Response" if service.outputs else None,
        schema=_schema(service.outputs),
        accepts_auth=service.accepts_auth,
    )


def _group_view(
    group: str, services: tuple[Service, ...], taken_types: frozenset[str]
) -> GroupView:
    """`taken_types` are the type names of earlier groups: every type is exported from the
    package root, so names must be unique across the whole SDK, not just within a group."""
    operations: tuple[OperationView, ...] = ()
    for service in services:
        code, short = split_operation(service.operation)
        # Compared case-insensitively: module names must also be unique on macOS and Windows.
        taken = {operation.name.lower() for operation in operations}
        name = _lower_first(short)
        name = f"{name}{code}" if name.lower() in taken or name in _RESERVED else name
        view = _operation_view(service, name, _upper_first(name))
        used = taken_types | {item for operation in operations for item in operation.exported_types}
        if used & set(view.exported_types):
            # The same service published in two groups: keep the method name, qualify the types.
            view = _operation_view(service, name, f"{_upper_first(name)}{code}")
        operations = (*operations, view)

    return GroupView(
        name=_camel(group),
        class_name=_upper_first(_camel(group)),
        endpoints=tuple(sorted({service.endpoint for service in services})),
        operations=operations,
    )


def _group_views(api: Api) -> tuple[GroupView, ...]:
    groups: tuple[GroupView, ...] = ()
    for name, services in api.groups.items():
        taken = frozenset(
            item
            for group in groups
            for operation in group.operations
            for item in operation.exported_types
        )
        groups = (*groups, _group_view(name, services, taken))
    names = [item for group in groups for op in group.operations for item in op.exported_types]
    duplicates = sorted({name for name in names if names.count(name) > 1})
    if duplicates:
        raise ValueError(f"These TypeScript types would be defined twice: {duplicates}")
    return groups


def emit(api: Api, package_dir: Path) -> list[Path]:
    """Write every generated module into `package_dir`/src and format them."""
    environment = Environment(
        loader=PackageLoader("xyp_generator.typescript", "templates"),
        undefined=StrictUndefined,
        trim_blocks=True,
        lstrip_blocks=True,
        keep_trailing_newline=True,
    )
    environment.filters["json"] = lambda value: json.dumps(value, ensure_ascii=False)

    def render(template: str, **context: object) -> str:
        return environment.get_template(template).render(**context)

    source = package_dir / "src"
    groups = _group_views(api)
    outputs = {
        source / "registry.ts": render("registry.ts.j2", api=api),
        source / "groups.ts": render("groups.ts.j2", groups=groups),
        source / "types" / "index.ts": render("types_index.ts.j2", groups=groups),
    }
    for group in groups:
        outputs = {
            **outputs,
            source / "services" / f"{group.name}.ts": render("service.ts.j2", group=group),
            source / "types" / f"{group.name}.ts": render("types.ts.j2", group=group),
            **{
                source / "operations" / group.name / f"{operation.name}.ts": render(
                    "operation.ts.j2", group=group, operation=operation
                )
                for operation in group.operations
            },
        }

    # These directories hold nothing but generated code, so stale modules are removed first.
    for name in _GENERATED_DIRS:
        shutil.rmtree(source / name, ignore_errors=True)
    for path, content in outputs.items():
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")
    _format(package_dir)
    return list(outputs)


def _format(package_dir: Path) -> None:
    # `check --write` formats and sorts imports, so generated code passes the package's own lint.
    command = ["pnpm", "exec", "biome", "check", "--write", "src"]
    subprocess.run(command, cwd=package_dir, check=True, capture_output=True)
