import json
import os
import pathlib
import sqlite3
import ssl
import sys

import cory_sandbox


def main() -> int:
    paths = cory_sandbox.bootstrap()
    if paths is None:
        raise RuntimeError("Cory sandbox paths are not configured")

    probe = {
        "version": sys.version.split()[0],
        "prefix": sys.prefix,
        "base_prefix": sys.base_prefix,
        "cwd": str(pathlib.Path.cwd()),
        "sandbox_root": str(paths.root),
        "workspace": str(paths.workspace),
        "site_packages": str(paths.site_packages),
        "tmp": os.environ.get("TMPDIR", ""),
        "dev": str(paths.dev),
        "ssl": ssl.OPENSSL_VERSION,
        "sqlite": sqlite3.sqlite_version,
    }
    (paths.state / "cory_python_probe.json").write_text(
        json.dumps(probe, indent=2),
        encoding="utf-8",
    )
    print(f"cory sandbox ready {probe['version']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
