#!/usr/bin/env python3
"""
Package shell-deps binaries into a tarball for upload to S3.

Usage:
    python3 scripts/package_shell_deps.py

Creates: shell-deps-arm64-v8a.tgz containing:
    arm64-v8a/libbash.so
    arm64-v8a/libbusybox.so

Upload to S3:
    aws s3 cp shell-deps-arm64-v8a.tgz \
        s3://cory-android-ci-manual-publicartifactsbucket-kmzlqxd3q1il/builds/prebuilt/latest/
"""
from __future__ import annotations

import tarfile
from pathlib import Path


def repo_root() -> Path:
    return Path(__file__).resolve().parent.parent


def main() -> int:
    root = repo_root()
    jniLibs = root / "terminal-core" / "terminal" / "src" / "main" / "jniLibs"
    output = root / "shell-deps-arm64-v8a.tgz"

    binaries = list((jniLibs / "arm64-v8a").glob("*.so"))
    if not binaries:
        print(f"ERROR: No .so files found in {jniLibs / 'arm64-v8a'}")
        return 1

    with tarfile.open(output, "w:gz") as tar:
        for so_file in sorted(binaries):
            arcname = f"arm64-v8a/{so_file.name}"
            tar.add(so_file, arcname=arcname)
            print(f"  added {arcname} ({so_file.stat().st_size:,} bytes)")

    print(f"\nCreated {output} ({output.stat().st_size:,} bytes)")
    print(f"\nUpload with:")
    print(f"  aws s3 cp {output} s3://cory-android-ci-manual-publicartifactsbucket-kmzlqxd3q1il/builds/prebuilt/latest/")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
