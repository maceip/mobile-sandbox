from __future__ import annotations

import json
import os
import pathlib
import sys
from dataclasses import dataclass


DEV_FILES = ("null", "zero", "random", "urandom", "tty", "stdin", "stdout", "stderr")
BUILTIN_COMMANDS = (
    "busybox",
    "cat",
    "cp",
    "echo",
    "env",
    "false",
    "find",
    "git",
    "head",
    "ls",
    "mkdir",
    "mv",
    "node",
    "pwd",
    "python",
    "python3",
    "rg",
    "rm",
    "sh",
    "tail",
    "touch",
    "true",
    "uname",
    "wc",
    "which",
)


@dataclass(frozen=True)
class SandboxPaths:
    root: pathlib.Path
    workspace: pathlib.Path
    tmp: pathlib.Path
    state: pathlib.Path
    site_packages: pathlib.Path
    bin: pathlib.Path
    dev: pathlib.Path
    home: pathlib.Path


_BOOTSTRAPPED = False
_PATHS: SandboxPaths | None = None


def _build_paths() -> SandboxPaths | None:
    sandbox_root = os.environ.get("CORY_SANDBOX_ROOT")
    if not sandbox_root:
        return None
    root = pathlib.Path(sandbox_root)
    return SandboxPaths(
        root=root,
        workspace=root / "workspace",
        tmp=root / "tmp",
        state=root / "state",
        site_packages=root / "site-packages",
        bin=root / "bin",
        dev=root / "dev",
        home=root / "home",
    )


def bootstrap() -> SandboxPaths | None:
    global _BOOTSTRAPPED, _PATHS
    if _BOOTSTRAPPED:
        return _PATHS

    paths = _build_paths()
    if paths is None:
        return None

    for path in (
        paths.root,
        paths.workspace,
        paths.tmp,
        paths.state,
        paths.site_packages,
        paths.bin,
        paths.dev,
        paths.home,
    ):
        path.mkdir(parents=True, exist_ok=True)

    for name in DEV_FILES:
        (paths.dev / name).touch(exist_ok=True)

    for command in BUILTIN_COMMANDS:
        placeholder = paths.bin / command
        if not placeholder.exists():
            placeholder.write_text(
                f"# Cory sandbox command placeholder for {command}\n",
                encoding="utf-8",
            )

    os.environ.setdefault("HOME", str(paths.home))
    os.environ.setdefault("TMPDIR", str(paths.tmp))
    os.environ["PATH"] = str(paths.bin)
    os.environ["CORY_SANDBOX_DEV"] = str(paths.dev)
    os.environ["CORY_SANDBOX_STATE"] = str(paths.state)

    for candidate in (str(paths.site_packages), str(paths.workspace)):
        if candidate not in sys.path:
            sys.path.insert(0, candidate)

    import cory_process

    cory_process.install()

    manifest = {
        "root": str(paths.root),
        "workspace": str(paths.workspace),
        "tmp": str(paths.tmp),
        "state": str(paths.state),
        "site_packages": str(paths.site_packages),
        "bin": str(paths.bin),
        "dev": str(paths.dev),
        "home": str(paths.home),
        "python": sys.version.split()[0],
    }
    (paths.state / "bootstrap.json").write_text(
        json.dumps(manifest, indent=2),
        encoding="utf-8",
    )

    _BOOTSTRAPPED = True
    _PATHS = paths
    return paths


def get_paths() -> SandboxPaths | None:
    return _PATHS if _BOOTSTRAPPED else bootstrap()
