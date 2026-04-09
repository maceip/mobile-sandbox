#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import os
import shutil
import subprocess
import sys
import tarfile
import tempfile
from pathlib import Path


class BuildFailure(RuntimeError):
    pass


def log(message: str) -> None:
    print(f"[s3-cached-build] {message}")


def run(command: list[str], *, cwd: Path | None = None, env: dict[str, str] | None = None) -> None:
    log("exec: " + " ".join(command))
    merged_env = dict(os.environ)
    if env:
        merged_env.update(env)
    result = subprocess.run(command, cwd=str(cwd) if cwd else None, env=merged_env)
    if result.returncode != 0:
        raise BuildFailure(f"command failed ({result.returncode}): {' '.join(command)}")


def capture(command: list[str]) -> str:
    result = subprocess.run(command, check=True, text=True, capture_output=True)
    return result.stdout.strip()


def repo_root() -> Path:
    return Path(__file__).resolve().parent.parent


def cache_key(root: Path) -> str:
    digest = hashlib.sha256()
    for rel in (
        Path("docker/android-build/Dockerfile"),
        Path("docker/android-build/entrypoint.sh"),
        Path("scripts/build_all_assets.py"),
        Path("codebuild/node/build-node-android.sh"),
    ):
        digest.update(rel.as_posix().encode("utf-8"))
        digest.update((root / rel).read_bytes())
    return digest.hexdigest()[:16]


def ensure_bucket(bucket: str, region: str) -> None:
    try:
        run(["aws", "s3api", "head-bucket", "--bucket", bucket])
    except BuildFailure:
        run(
            [
                "aws",
                "s3api",
                "create-bucket",
                "--bucket",
                bucket,
                "--region",
                region,
                "--create-bucket-configuration",
                f"LocationConstraint={region}",
            ]
        )
        run(
            [
                "aws",
                "s3api",
                "put-public-access-block",
                "--bucket",
                bucket,
                "--public-access-block-configuration",
                "BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true",
            ]
        )
        run(
            [
                "aws",
                "s3api",
                "put-bucket-ownership-controls",
                "--bucket",
                bucket,
                "--ownership-controls",
                "Rules=[{ObjectOwnership=BucketOwnerEnforced}]",
            ]
        )
        run(
            [
                "aws",
                "s3api",
                "put-bucket-encryption",
                "--bucket",
                bucket,
                "--server-side-encryption-configuration",
                '{"Rules":[{"ApplyServerSideEncryptionByDefault":{"SSEAlgorithm":"AES256"},"BucketKeyEnabled":true}]}',
            ]
        )
        run(
            [
                "aws",
                "s3api",
                "put-bucket-versioning",
                "--bucket",
                bucket,
                "--versioning-configuration",
                "Status=Enabled",
            ]
        )


def restore_tar(bucket: str, key: str, target_dir: Path) -> bool:
    with tempfile.TemporaryDirectory() as temp_dir:
        archive = Path(temp_dir) / "cache.tgz"
        result = subprocess.run(["aws", "s3", "cp", f"s3://{bucket}/{key}", str(archive)])
        if result.returncode != 0:
            return False
        if target_dir.exists():
            shutil.rmtree(target_dir)
        target_dir.mkdir(parents=True, exist_ok=True)
        with tarfile.open(archive, "r:gz") as tar:
            tar.extractall(target_dir)
        return True


def upload_tar(bucket: str, key: str, source_dir: Path) -> None:
    with tempfile.TemporaryDirectory() as temp_dir:
        archive = Path(temp_dir) / "cache.tgz"
        with tarfile.open(archive, "w:gz") as tar:
            tar.add(source_dir, arcname=".")
        run(["aws", "s3", "cp", str(archive), f"s3://{bucket}/{key}"])


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Restore/persist Cory build caches from S3 around a Docker build.")
    parser.add_argument("--bucket", required=True, help="S3 bucket for build caches")
    parser.add_argument("--region", default=os.environ.get("AWS_REGION", "eu-central-1"))
    parser.add_argument("--image", default="cory-android-build")
    parser.add_argument("--android-sdk-root", default="/opt/android-sdk")
    parser.add_argument("--android-ndk-version", default="30.0.14904198")
    parser.add_argument("--cache-prefix", default="cory/build-cache")
    parser.add_argument("build_args", nargs="*", help="extra args passed to scripts/build_all_assets.py inside the container")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    root = repo_root()
    key_suffix = cache_key(root)
    cache_root = root / ".cache" / "container-build"
    gradle_dir = cache_root / "gradle"
    cargo_registry_dir = cache_root / "cargo" / "registry"
    cargo_git_dir = cache_root / "cargo" / "git"
    uv_dir = cache_root / "uv"
    for path in (gradle_dir, cargo_registry_dir, cargo_git_dir, uv_dir):
        path.mkdir(parents=True, exist_ok=True)

    try:
        ensure_bucket(args.bucket, args.region)

        for name, directory in (
            ("gradle", gradle_dir),
            ("cargo-registry", cargo_registry_dir),
            ("cargo-git", cargo_git_dir),
            ("uv", uv_dir),
        ):
            restored = restore_tar(args.bucket, f"{args.cache_prefix}/{key_suffix}/{name}.tgz", directory)
            log(f"{name} cache {'restored' if restored else 'not found'}")

        run(
            [
                "docker",
                "build",
                "-t",
                args.image,
                "-f",
                str(root / "docker" / "android-build" / "Dockerfile"),
                str(root),
            ]
        )

        run(
            [
                "docker",
                "run",
                "--rm",
                "-e",
                f"ANDROID_SDK_ROOT={args.android_sdk_root}",
                "-e",
                f"ANDROID_NDK_VERSION={args.android_ndk_version}",
                "-v",
                f"{root}:/workspace/Cory",
                "-v",
                f"{gradle_dir}:/root/.gradle",
                "-v",
                f"{cargo_registry_dir}:/root/.cargo/registry",
                "-v",
                f"{cargo_git_dir}:/root/.cargo/git",
                "-v",
                f"{uv_dir}:/root/.cache/uv",
                args.image,
                *args.build_args,
            ]
        )

        for name, directory in (
            ("gradle", gradle_dir),
            ("cargo-registry", cargo_registry_dir),
            ("cargo-git", cargo_git_dir),
            ("uv", uv_dir),
        ):
            upload_tar(args.bucket, f"{args.cache_prefix}/{key_suffix}/{name}.tgz", directory)
            log(f"{name} cache uploaded")
    except BuildFailure as exc:
        print(str(exc), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
