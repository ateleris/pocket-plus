"""Parse a per-adapter `adapter.toml` and resolve where its real codec lives."""

from __future__ import annotations

import re
import tomllib
from collections.abc import Mapping
from dataclasses import dataclass
from pathlib import Path

_VAR = re.compile(r"\$\{([^}]+)\}")
COMMANDS = ("build", "validate", "bench", "profile", "conformance", "explain", "sweep")


@dataclass(frozen=True)
class Manifest:
    """Everything pocketbench needs before it can build and run a wrapper.

    Descriptive metadata (ops, timing tier, conformance, conformance support) is NOT here; it is
    self-reported by the built wrapper via `capabilities`, so it cannot drift from the code.
    """

    name: str
    language: str | None
    source: str | None       # raw string from TOML; None => self-contained (e.g. the mock)
    build: str | None        # raw command template; None => nothing to build
    entrypoint: str          # required; relative to `dir`
    interpreter: str | None  # e.g. "python"; None => run the entrypoint directly
    version_cmd: str | None
    dir: Path                # absolute adapter directory (where adapter.toml lives)
    example: bool = False    # True => a test double, excluded from real runs but still
    #                          returned by discover_adapters so the unit tests can build it.
    # Commands whose default and glob selection leave this adapter out; naming it still runs it.
    skip_commands: tuple[str, ...] = ()

    @property
    def size_specialized(self) -> bool:
        """The build compiles in the packet size, so bench rebuilds it per dataset size group."""
        return self.build is not None and "{max_packet_bits}" in self.build


def load_manifest(adapter_dir: Path) -> Manifest:
    """Load `adapter_dir/adapter.toml`. `name` defaults to the folder name."""
    adapter_dir = adapter_dir.resolve()
    with (adapter_dir / "adapter.toml").open("rb") as fh:
        data = tomllib.load(fh)
    entrypoint = data.get("entrypoint")
    if not entrypoint:
        raise ValueError(f"{adapter_dir}/adapter.toml: 'entrypoint' is required")
    skip = tuple(data.get("skip_commands", ()))
    unknown = [c for c in skip if c not in COMMANDS]
    if unknown:
        raise ValueError(f"{adapter_dir}/adapter.toml: unknown skip_commands {unknown}; "
                         f"known: {', '.join(COMMANDS)}")
    return Manifest(
        name=data.get("name", adapter_dir.name),
        language=data.get("language"),
        source=data.get("source"),
        build=data.get("build"),
        entrypoint=entrypoint,
        interpreter=data.get("interpreter"),
        version_cmd=data.get("version_cmd"),
        dir=adapter_dir,
        example=bool(data.get("example", False)),
        skip_commands=skip,
    )


def _expand(raw: str, variables: Mapping[str, str | Path]) -> str:
    def sub(match: re.Match[str]) -> str:
        key = match.group(1)
        if key not in variables:
            raise KeyError(f"unknown settings variable ${{{key}}} in adapter source")
        return str(variables[key])

    return _VAR.sub(sub, raw)


def resolve_source(
    manifest: Manifest, *, repo_root: Path, variables: Mapping[str, str | Path]
) -> Path | None:
    """Resolve a manifest's codec location to an absolute path (None if self-contained).

    A `${var}` is expanded from `variables` (the usual case, since codecs live outside this repo
    at machine-specific locations); a resulting relative path is taken relative to `repo_root`
    (e.g. `../pocketrust`, a sibling of the enclosing repo).
    """
    if manifest.source is None:
        return None
    expanded = Path(_expand(manifest.source, variables))
    return expanded if expanded.is_absolute() else (repo_root / expanded)
