"""Render the Python SDK's generated modules from the IR."""

from __future__ import annotations

import shutil
import subprocess
from dataclasses import dataclass
from pathlib import Path

from jinja2 import Environment, PackageLoader, StrictUndefined

from xyp_generator.ir import Api, Field, Service, TypeRef
from xyp_generator.naming import pascal_case, snake_case, split_operation
from xyp_generator.python.names import python_name, unique_names

_SCALARS = {
    "string": "str",
    "int": "int",
    "float": "float",
    "bool": "bool",
    "decimal": "Decimal",
    "any": "Any",
}
_OUTPUT_ONLY = {"bytes": "Base64Bytes", "date": "XypDate"}
_INPUT_ONLY = {"bytes": "bytes", "date": "datetime | date | str"}
_RESERVED_PARAMETERS = ("self", "auth", "operator")


@dataclass(frozen=True)
class FieldView:
    name: str
    wire_name: str
    annotation: str
    doc: str


@dataclass(frozen=True)
class ModelView:
    name: str
    doc: str
    fields: tuple[FieldView, ...]


@dataclass(frozen=True)
class MethodView:
    name: str
    operation: str
    doc: str
    organization: str
    parameters: tuple[FieldView, ...]
    models: tuple[ModelView, ...]  # nested models first, the response model last
    accepts_auth: bool
    returns: str | None  # response model class name; None -> raw data


@dataclass(frozen=True)
class GroupView:
    name: str  # "labor_welfare"
    class_name: str  # "LaborWelfare"
    endpoints: tuple[str, ...]
    methods: tuple[MethodView, ...]

    @property
    def model_names(self) -> tuple[str, ...]:
        return tuple(sorted(model.name for method in self.methods for model in method.models))


def _clean_doc(text: str) -> str:
    return " ".join(text.replace("\\", "/").replace('"""', "'''").split())


def _output_annotation(type_ref: TypeRef, model_name: str) -> str:
    if type_ref.kind == "list" and type_ref.item:
        return f"list[{_output_annotation(type_ref.item, model_name)}]"
    if type_ref.kind == "object":
        return model_name
    return _OUTPUT_ONLY.get(type_ref.kind) or _SCALARS[type_ref.kind]


def _input_annotation(type_ref: TypeRef) -> str:
    if type_ref.kind == "list" and type_ref.item:
        return f"Sequence[{_input_annotation(type_ref.item)}]"
    if type_ref.kind == "object":
        return "Mapping[str, Any]"
    return _INPUT_ONLY.get(type_ref.kind) or _SCALARS[type_ref.kind]


def _models_for(prefix: str, doc: str, fields: tuple[Field, ...]) -> tuple[ModelView, ...]:
    """Depth-first, children before parents, so every annotation refers to a defined class."""
    names = unique_names(field.wire_name for field in fields)
    nested: tuple[ModelView, ...] = ()
    views: tuple[FieldView, ...] = ()
    for field in fields:
        child_name = f"{prefix}{pascal_case(field.wire_name)}"
        if field.children:
            nested = (*nested, *_models_for(child_name, field.doc, field.children))
        views = (
            *views,
            FieldView(
                name=names[field.wire_name],
                wire_name=field.wire_name,
                annotation=_output_annotation(field.type, child_name),
                doc=_clean_doc(field.doc),
            ),
        )
    return (*nested, ModelView(name=prefix, doc=_clean_doc(doc), fields=views))


def _method_view(service: Service, name: str, models: tuple[ModelView, ...]) -> MethodView:
    names = unique_names((field.wire_name for field in service.inputs), _RESERVED_PARAMETERS)
    return MethodView(
        name=name,
        operation=service.operation,
        doc=_clean_doc(service.doc),
        organization=_clean_doc(service.organization),
        parameters=tuple(
            FieldView(
                name=names[field.wire_name],
                wire_name=field.wire_name,
                annotation=_input_annotation(field.type),
                doc=_clean_doc(field.doc),
            )
            for field in service.inputs
        ),
        models=models,
        accepts_auth=service.accepts_auth,
        returns=models[-1].name if models else None,
    )


def _response_models(service: Service, base: str) -> tuple[ModelView, ...]:
    """Nested models first, then the root renamed to `<Base>Response`."""
    *nested, root = _models_for(base, "", service.outputs)
    doc = _clean_doc(f"{service.operation}: {service.doc}")
    return (*nested, ModelView(f"{base}Response", doc, root.fields))


def _group_view(name: str, services: tuple[Service, ...]) -> GroupView:
    methods: tuple[MethodView, ...] = ()
    for service in services:
        code, short = split_operation(service.operation)
        taken_methods = {method.name for method in methods}
        taken_classes = {model.name for method in methods for model in method.models}
        # The method name is also the operation's module name, hence the python_name rules.
        method = python_name(snake_case(short))
        base = pascal_case(short)
        # Two services can share a name and differ only by code (WS100101_x / WS200101_x).
        method = f"{method}_{code.lower()}" if method in taken_methods else method
        base = f"{base}{code}" if f"{base}Response" in taken_classes else base
        models = _response_models(service, base) if service.outputs else ()
        methods = (*methods, _method_view(service, method, models))

    _require_unique(name, [model.name for method in methods for model in method.models])
    return GroupView(
        name=name,
        class_name=pascal_case(name),
        endpoints=tuple(sorted({service.endpoint for service in services})),
        methods=methods,
    )


def _require_unique(group: str, class_names: list[str]) -> None:
    duplicates = sorted({name for name in class_names if class_names.count(name) > 1})
    if duplicates:
        raise ValueError(f"Group {group!r} would define these classes twice: {duplicates}")


_GENERATED_PACKAGES = {
    "operations": "One module per XYP service: its response models and its sync/async call.",
    "services": "One module per group: the service classes behind `xyp.<group>`.",
    "models": "Response models re-exported per group, for type annotations.",
}


def _init(doc: str) -> str:
    return f'"""GENERATED by xyp-generator. Do not edit.\n\n{doc}\n"""\n'


def emit(api: Api, package_dir: Path) -> list[Path]:
    """Write every generated module into `package_dir` (…/src/xyp) and format them."""
    environment = Environment(
        loader=PackageLoader("xyp_generator.python", "templates"),
        undefined=StrictUndefined,
        trim_blocks=True,
        lstrip_blocks=True,
        keep_trailing_newline=True,
    )
    environment.filters["pyrepr"] = repr

    def render(template: str, **context: object) -> str:
        return environment.get_template(template).render(**context)

    groups = tuple(_group_view(name, services) for name, services in api.groups.items())
    outputs = {
        package_dir / "_operations.py": render("operations.py.j2", api=api),
        package_dir / "_groups.py": render("groups.py.j2", groups=groups),
        **{
            package_dir / name / "__init__.py": _init(doc)
            for name, doc in _GENERATED_PACKAGES.items()
        },
    }
    for group in groups:
        operations_dir = package_dir / "operations" / group.name
        outputs = {
            **outputs,
            package_dir / "services" / f"{group.name}.py": render("service.py.j2", group=group),
            package_dir / "models" / f"{group.name}.py": render("models.py.j2", group=group),
            operations_dir / "__init__.py": _init(f"Services of the `{group.name}` group."),
            **{
                operations_dir / f"{method.name}.py": render(
                    "operation.py.j2", group=group, method=method
                )
                for method in group.methods
            },
        }

    # These packages hold nothing but generated code, so stale modules are removed first.
    for name in _GENERATED_PACKAGES:
        shutil.rmtree(package_dir / name, ignore_errors=True)
    for path, content in outputs.items():
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")
    _format(list(outputs))
    return list(outputs)


def _format(paths: list[Path]) -> None:
    files = [str(path) for path in paths]
    subprocess.run(["ruff", "check", "--quiet", "--fix", "--select", "F401,I", *files], check=True)
    subprocess.run(["ruff", "format", "--quiet", *files], check=True)
