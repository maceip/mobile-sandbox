#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import os
import shutil
import subprocess
import sys
from contextlib import contextmanager
from pathlib import Path
class BuildFailure(RuntimeError):
    pass


def log(message: str) -> None:
    print(f"[build-all-assets] {message}")


def run(command: list[str], *, cwd: Path | None = None, env: dict[str, str] | None = None) -> None:
    log("exec: " + " ".join(command))
    merged_env = dict(os.environ)
    if env:
        merged_env.update(env)
    result = subprocess.run(command, cwd=str(cwd) if cwd else None, env=merged_env)
    if result.returncode != 0:
        raise BuildFailure(
            f"command failed ({result.returncode}): {' '.join(command)}"
        )


def require_tool(name: str) -> str:
    path = shutil.which(name)
    if not path:
        raise BuildFailure(f"required tool not found on PATH: {name}")
    return path


def repo_root() -> Path:
    return Path(__file__).resolve().parent.parent


def android_env(root: Path) -> dict[str, str]:
    sdk_root = os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME")
    if not sdk_root:
        raise BuildFailure(
            "ANDROID_SDK_ROOT or ANDROID_HOME must be set for ripgrep and app builds"
        )
    env = {
        "ANDROID_SDK_ROOT": sdk_root,
        "ANDROID_HOME": sdk_root,
    }

    cargo_home = Path(os.environ.get("CARGO_HOME", str(Path.home() / ".cargo")))
    cargo_bin = cargo_home / "bin"
    current_path = os.environ.get("PATH", "")
    env["PATH"] = str(cargo_bin) + os.pathsep + current_path

    return env


@contextmanager
def managed_local_properties(root: Path, env: dict[str, str]):
    local_props = root / "local.properties"
    original_contents = local_props.read_text(encoding="utf-8") if local_props.exists() else None

    lines = [f"sdk.dir={env['ANDROID_SDK_ROOT'].replace(chr(92), '/')}"]
    ndk_dir = Path(env["ANDROID_SDK_ROOT"]) / "ndk" / os.environ.get("ANDROID_NDK_VERSION", "30.0.14904198")
    if ndk_dir.exists():
        lines.append(f"ndk.dir={str(ndk_dir).replace(chr(92), '/')}")

    local_props.write_text("\n".join(lines) + "\n", encoding="utf-8")
    log(f"wrote {local_props}")
    try:
        yield
    finally:
        if original_contents is None:
            try:
                local_props.unlink()
                log(f"removed temporary {local_props}")
            except FileNotFoundError:
                pass
        else:
            local_props.write_text(original_contents, encoding="utf-8")
            log(f"restored {local_props}")


def build_node(root: Path) -> None:
    require_tool("bash")
    script = root / "codebuild" / "node" / "build-node-android.sh"
    for mode in ("build", "package"):
        run(["bash", str(script), mode], cwd=script.parent)
    packaged_root = root / "codebuild" / "node" / "out" / "third_party" / "node24-android"
    if not packaged_root.exists():
        raise BuildFailure(f"missing packaged Node bundle: {packaged_root}")
    target_root = root / "third_party" / "node24-android"
    if target_root.exists():
        shutil.rmtree(target_root)
    shutil.copytree(packaged_root, target_root)
    log(f"synced {packaged_root} -> {target_root}")


def build_ripgrep(root: Path, env: dict[str, str]) -> None:
    require_tool("cargo")
    require_tool("cargo-ndk")
    ripgrep_dir = root / "third_party" / "ripgrep"
    run(
        ["cargo", "ndk", "-t", "arm64-v8a", "--platform", "24", "build", "--release"],
        cwd=ripgrep_dir,
        env=env,
    )


def build_app(root: Path, env: dict[str, str]) -> None:
    gradlew = root / "gradlew"
    if not gradlew.exists():
        raise BuildFailure(f"missing Gradle wrapper: {gradlew}")
    run([str(gradlew), ":app:assembleDebug", "--console=plain"], cwd=root, env=env)


def require_file(path: Path) -> Path:
    if not path.is_file():
        raise BuildFailure(f"expected build artifact missing: {path}")
    return path


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def expected_artifacts(
    root: Path,
    *,
    expect_node: bool,
    expect_ripgrep: bool,
    expect_app: bool,
) -> dict[str, Path]:
    artifacts: dict[str, Path] = {}
    if expect_node:
        artifacts["node_bin"] = root / "third_party" / "node24-android" / "bin" / "arm64-v8a" / "node"
        artifacts["node_lib"] = root / "third_party" / "node24-android" / "lib" / "arm64-v8a" / "liblibnode.a"
    if expect_ripgrep:
        artifacts["ripgrep"] = root / "third_party" / "ripgrep" / "target" / "aarch64-linux-android" / "release" / "rg"
    if expect_app:
        artifacts["busybox"] = root / "third_party" / "ndk-busybox-ref" / "libs" / "arm64-v8a" / "busybox"
        artifacts["rust_lib"] = root / "rust" / "cory_rust" / "target" / "aarch64-linux-android" / "debug" / "libcory_rust.a"
        artifacts["apk"] = root / "app" / "build" / "outputs" / "apk" / "debug" / "app-debug.apk"
    return artifacts


def build_report_lines(artifacts: dict[str, Path], *, status: str, failure: str | None) -> list[str]:
    lines = [f"status={status}"]
    if failure:
        lines.append(f"failure={failure}")
    for label, path in artifacts.items():
        lines.append(f"{label}={path}")
        exists = path.is_file()
        lines.append(f"{label}_exists={'1' if exists else '0'}")
        if exists:
            lines.append(f"{label}_size={path.stat().st_size}")
            lines.append(f"{label}_sha256={sha256_file(path)}")
    return lines


def write_report(
    root: Path,
    *,
    expect_node: bool,
    expect_ripgrep: bool,
    expect_app: bool,
    status: str,
    failure: str | None = None,
) -> Path:
    report_path = root / "build-all-assets-report.txt"
    report_lines = build_report_lines(
        expected_artifacts(
            root,
            expect_node=expect_node,
            expect_ripgrep=expect_ripgrep,
            expect_app=expect_app,
        ),
        status=status,
        failure=failure,
    )
    report_path.write_text("\n".join(report_lines) + "\n", encoding="utf-8")
    log(f"wrote {report_path}")
    return report_path


def write_manifest(
    root: Path,
    *,
    expect_node: bool,
    expect_ripgrep: bool,
    expect_app: bool,
) -> None:
    manifest_path = root / "build-all-assets-manifest.txt"
    artifacts = expected_artifacts(
        root,
        expect_node=expect_node,
        expect_ripgrep=expect_ripgrep,
        expect_app=expect_app,
    )
    lines = []
    for label, path in artifacts.items():
        require_file(path)
        lines.append(f"{label}={path}")
        lines.append(f"{label}_size={path.stat().st_size}")
        lines.append(f"{label}_sha256={sha256_file(path)}")
    manifest_path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    log(f"wrote {manifest_path}")


def doctor(root: Path) -> None:
    log("checking toolchain")
    for tool in ("bash", "git", "java", "python3"):
        require_tool(tool)
    android_sdk = os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME")
    if android_sdk:
        log(f"android sdk: {android_sdk}")
    else:
        log("android sdk not configured yet")
    for path in (
        root / "codebuild" / "node" / "build-node-android.sh",
        root / "app" / "build.gradle",
        root / "third_party" / "python-android" / "prefix" / "lib" / "libpython3.14.so",
        root / "third_party" / "libgit2",
        root / "third_party" / "ndk-busybox-ref" / "Android.mk",
        root / "rust" / "cory_rust" / "Cargo.toml",
    ):
        if not path.exists():
            raise BuildFailure(f"missing required path: {path}")
    log("doctor OK")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Build Cory's vendored assets and Android app from one entry point."
    )
    parser.add_argument(
        "--skip-node",
        action="store_true",
        help="Do not rebuild the vendored Android Node bundle",
    )
    parser.add_argument(
        "--skip-ripgrep",
        action="store_true",
        help="Do not rebuild the Android ripgrep binary",
    )
    parser.add_argument(
        "--skip-app",
        action="store_true",
        help="Do not assemble the Android app",
    )
    parser.add_argument(
        "--doctor-only",
        action="store_true",
        help="Only validate prerequisites",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    root = repo_root()
    expect_node = not args.skip_node
    expect_ripgrep = not args.skip_ripgrep
    expect_app = not args.skip_app
    try:
        doctor(root)
        if args.doctor_only:
            write_report(
                root,
                expect_node=expect_node,
                expect_ripgrep=expect_ripgrep,
                expect_app=expect_app,
                status="doctor_ok",
            )
            return 0
        env = android_env(root)
        with managed_local_properties(root, env):
            if not args.skip_node:
                build_node(root)
            if not args.skip_ripgrep:
                build_ripgrep(root, env)
            if not args.skip_app:
                build_app(root, env)
        write_manifest(root, expect_node=expect_node, expect_ripgrep=expect_ripgrep, expect_app=expect_app)
        write_report(
            root,
            expect_node=expect_node,
            expect_ripgrep=expect_ripgrep,
            expect_app=expect_app,
            status="success",
        )
    except BuildFailure as exc:
        write_report(
            root,
            expect_node=expect_node,
            expect_ripgrep=expect_ripgrep,
            expect_app=expect_app,
            status="failure",
            failure=str(exc),
        )
        print(str(exc), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
