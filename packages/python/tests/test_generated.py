"""Checks over the generated code as a whole."""

from __future__ import annotations

import importlib
import inspect
import json
import pkgutil
import subprocess
import sys
from pathlib import Path

import xyp.models
import xyp.services
from xyp._groups import AsyncGroups, SyncGroups
from xyp._operations import NAMESPACES, OPERATIONS

SPEC = Path(__file__).resolve().parents[3] / "spec" / "services.json"


def _group_names() -> list[str]:
    return [module.name for module in pkgutil.iter_modules(xyp.services.__path__)]


def test_every_service_in_the_spec_is_callable() -> None:
    operations = {row["operationName"] for row in json.loads(SPEC.read_text(encoding="utf-8"))}
    assert set(OPERATIONS) == operations

    documented: set[str] = set()
    for name in _group_names():
        module = importlib.import_module(f"xyp.services.{name}")
        for _, service in inspect.getmembers(module, inspect.isclass):
            if service.__module__ != module.__name__ or service.__name__.startswith("Async"):
                continue
            for _, method in inspect.getmembers(service, inspect.isfunction):
                if method.__doc__ and not method.__name__.startswith("_"):
                    documented.add(method.__doc__.split(":")[0].split()[0])
    assert documented == operations


def test_sync_and_async_expose_the_same_api() -> None:
    sync_groups = {name for name in vars(SyncGroups) if not name.startswith("_")}
    async_groups = {name for name in vars(AsyncGroups) if not name.startswith("_")}
    assert sync_groups == async_groups == set(_group_names())

    for name in _group_names():
        module = importlib.import_module(f"xyp.services.{name}")
        classes = dict(inspect.getmembers(module, inspect.isclass))
        for class_name, service in classes.items():
            if class_name.startswith("Async") and service.__module__ == module.__name__:
                twin = classes[class_name.removeprefix("Async")]
                assert set(vars(service)) == set(vars(twin)), class_name


def test_models_import_without_warnings() -> None:
    for module in pkgutil.iter_modules(xyp.models.__path__):
        importlib.import_module(f"xyp.models.{module.name}")


def test_verified_namespaces() -> None:
    assert NAMESPACES["citizen-1.5.0"] == "http://citizen.xyp.gov.mn/"
    assert NAMESPACES["meta-1.5.0"] == "http://meta.xyp.gov.mn/"


def test_importing_xyp_does_not_import_service_groups() -> None:
    code = "import sys, xyp; print(any(m.startswith('xyp.services.') for m in sys.modules))"
    output = subprocess.run(
        [sys.executable, "-c", code], capture_output=True, text=True, check=True
    )
    assert output.stdout.strip() == "False"
