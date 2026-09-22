"""Emit the whole real catalog into a temporary directory and check what javac and
reviewers rely on. Compiling needs a JDK and the hand-written core, so that part
runs in the Java SDK's own build; these checks keep the generator honest without one."""

from __future__ import annotations

import re
from pathlib import Path

import pytest

from xyp_generator.ir import Api, Field, Service, TypeRef
from xyp_generator.java.emit import HEADER, SOURCE_ROOT, emit
from xyp_generator.spec import load_api

SPEC_DIR = Path(__file__).resolve().parents[2] / "spec"
CORE = SOURCE_ROOT / "io" / "github" / "baljinnyamday" / "xyp"

_JAVADOC = re.compile(r"/\*\*.*?\*/", re.DOTALL)
_STRING_LITERAL = re.compile(r'"(?:\\.|[^"\\\n])*"')
_IMPORT = re.compile(r"^import ([\w.]+)\.(\w+);$", re.MULTILINE)


@pytest.fixture(scope="module")
def api() -> Api:
    return load_api(SPEC_DIR)


@pytest.fixture(scope="module")
def emitted(api: Api, tmp_path_factory: pytest.TempPathFactory) -> tuple[Path, list[Path]]:
    root = tmp_path_factory.mktemp("java")
    return root, emit(api, root, format=False)


def test_every_service_is_in_the_registry(api: Api, emitted: tuple[Path, list[Path]]) -> None:
    root, _ = emitted
    registry = (root / CORE / "Registry.java").read_text(encoding="utf-8")
    for service in api.services:
        assert f'Map.entry("{service.operation}", "{service.endpoint}")' in registry
    entries = re.findall(r'Map\.entry\("([^"]+)"', registry.split("KNOWN_NAMESPACES")[0])
    assert entries == sorted(entries)


def test_every_service_has_a_client_method(api: Api, emitted: tuple[Path, list[Path]]) -> None:
    _, paths = emitted
    clients = "".join(
        path.read_text(encoding="utf-8") for path in paths if path.name.endswith("Client.java")
    )
    for service in api.services:
        assert f'"{service.operation}"' in clients


def test_file_names_are_unique_case_insensitively(emitted: tuple[Path, list[Path]]) -> None:
    _, paths = emitted
    lowered = [str(path).lower() for path in paths]
    assert len(set(lowered)) == len(lowered)
    assert all(path.is_file() for path in paths)


def test_every_file_starts_with_the_header(emitted: tuple[Path, list[Path]]) -> None:
    _, paths = emitted
    for path in paths:
        assert path.read_text(encoding="utf-8").startswith(f"{HEADER}\n"), path


def test_every_record_decoder_is_public(emitted: tuple[Path, list[Path]]) -> None:
    # Users pass X::decode to invoke and ResponseReader.decode, from their own packages.
    _, paths = emitted
    responses = [path for path in paths if path.name.endswith("Response.java")]
    assert responses
    for path in responses:
        text = path.read_text(encoding="utf-8")
        records = len(re.findall(r"^\s*public record ", text, re.MULTILINE))
        decoders = re.findall(
            r"^\s*(.*)static \w+ decode\(ResponseReader reader\)", text, re.MULTILINE
        )
        assert decoders == ["public "] * records, path


def test_comments_hold_no_backslash_and_no_early_end(emitted: tuple[Path, list[Path]]) -> None:
    _, paths = emitted
    for path in paths:
        source = path.read_text(encoding="utf-8")
        # javac decodes \u escapes everywhere, so outside string literals (where each
        # backslash is doubled) no backslash may appear at all.
        assert "\\" not in _STRING_LITERAL.sub('""', source), path
        # A stray `*/` in a description would close its comment early and leave the
        # rest of the text as code, so every opening must pair with exactly one close.
        without_docs = _JAVADOC.sub("", source)
        assert "/*" not in without_docs, path
        assert "*/" not in without_docs, path


def test_imports_are_exact(emitted: tuple[Path, list[Path]]) -> None:
    """google-java-format does not remove unused imports, so the templates must not
    write any: every imported simple name appears again in the file."""
    _, paths = emitted
    for path in paths:
        source = path.read_text(encoding="utf-8")
        imports = _IMPORT.findall(source)
        body = _IMPORT.sub("", source)
        for _, name in imports:
            assert re.search(rf"\b{name}\b", body), f"{path}: unused import {name}"
        names = [f"{package}.{name}" for package, name in imports]
        assert names == sorted(names), path


def test_module_exports_every_group_package(api: Api, emitted: tuple[Path, list[Path]]) -> None:
    root, _ = emitted
    module = (root / SOURCE_ROOT / "module-info.java").read_text(encoding="utf-8")
    exported = set(re.findall(r"exports ([\w.]+);", module))
    packages = {
        ".".join(path.parent.relative_to(root / SOURCE_ROOT).parts)
        for path in (root / CORE).glob("*/*Client.java")
    }
    assert len(packages) == len(api.groups)
    assert exported == {*packages, "io.github.baljinnyamday.xyp"}
    groups = (root / CORE / "XypGroups.java").read_text(encoding="utf-8")
    assert "public final LaborWelfareClient laborWelfare()" in groups


def test_emitting_twice_is_byte_identical(api: Api, tmp_path: Path) -> None:
    first = {path: path.read_bytes() for path in emit(api, tmp_path, format=False)}
    second = {path: path.read_bytes() for path in emit(api, tmp_path, format=False)}
    assert first == second


def _tiny_api(group: str, doc: str = "") -> Api:
    field = Field("regnum", doc, TypeRef("string"))
    service = Service("WS1_sample", doc, doc, f"{group}-1.5.0", group, (field,), (field,))
    return Api(services=(service,), namespaces={})


def test_stale_generated_packages_are_removed(tmp_path: Path) -> None:
    emit(_tiny_api("old_group"), tmp_path, format=False)
    stale = tmp_path / CORE / "oldgroup"
    assert (stale / "OldGroupClient.java").is_file()
    handwritten = tmp_path / CORE / "extra"
    handwritten.mkdir()
    (handwritten / "ExtraClient.java").write_text("package x;\n", encoding="utf-8")

    emit(_tiny_api("new_group"), tmp_path, format=False)
    assert not stale.exists()
    assert (handwritten / "ExtraClient.java").is_file()
    assert (tmp_path / CORE / "newgroup" / "NewGroupClient.java").is_file()


def test_hostile_catalog_text_stays_inside_its_comment(tmp_path: Path) -> None:
    doc = "a \\u002a/ b */ c <b> & @param {@code x}"
    for path in emit(_tiny_api("sample", doc), tmp_path, format=False):
        source = path.read_text(encoding="utf-8")
        assert "\\" not in source
        assert "*/" not in _JAVADOC.sub("", source)


def test_formatting_needs_the_maven_wrapper(tmp_path: Path) -> None:
    with pytest.raises(FileNotFoundError, match="format=False"):
        emit(_tiny_api("sample"), tmp_path)
    assert not (tmp_path / "src").exists()
