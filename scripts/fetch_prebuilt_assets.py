#!/usr/bin/env python3
from __future__ import annotations

import argparse
import os
import shutil
import sys
import tarfile
import tempfile
import urllib.error
import urllib.request
from pathlib import Path


class FetchError(RuntimeError):
    pass


ASSET_ARCHIVES = {
    "node": "node24-android.tgz",
    "python": "python-android-prefix.tgz",
    "busybox": "busybox-arm64-v8a.tgz",
    "ripgrep": "ripgrep-arm64-v8a.tgz",
    "rust": "rust-cory_rust-arm64-debug.tgz",
}

ASSET_TARGETS = {
    "node": Path("third_party"),
    "python": Path("third_party/python-android"),
    "busybox": Path("third_party/ndk-busybox-ref/libs"),
    "ripgrep": Path("third_party/ripgrep/target/aarch64-linux-android"),
    "rust": Path("rust/cory_rust/target/aarch64-linux-android"),
}

DEFAULT_REGION = "eu-central-1"
DEFAULT_BUCKET = "cory-android-ci-manual-publicartifactsbucket-kmzlqxd3q1il"
DEFAULT_PREFIX = "builds/prebuilt/latest"


def repo_root() -> Path:
    return Path(__file__).resolve().parent.parent


def log(message: str) -> None:
    print(f"[fetch-prebuilt-assets] {message}")


def public_asset_url(*, bucket: str, prefix: str, region: str, archive_name: str) -> str:
    clean_prefix = prefix.strip("/")
    return f"https://{bucket}.s3.{region}.amazonaws.com/{clean_prefix}/{archive_name}"


def download(url: str, destination: Path) -> None:
    log(f"download: {url}")
    try:
        with urllib.request.urlopen(url) as response, destination.open("wb") as output:
            shutil.copyfileobj(response, output)
    except urllib.error.HTTPError as exc:
        raise FetchError(f"download failed ({exc.code}): {url}") from exc
    except urllib.error.URLError as exc:
        raise FetchError(f"download failed: {url}: {exc.reason}") from exc


def safe_extract(archive_path: Path, destination: Path) -> None:
    with tarfile.open(archive_path, "r:gz") as archive:
        for member in archive.getmembers():
            member_path = (destination / member.name).resolve()
            if destination.resolve() not in member_path.parents and member_path != destination.resolve():
                raise FetchError(f"refusing to extract unsafe archive member: {member.name}")
        archive.extractall(destination)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Restore Cory prebuilt Android payloads from the public S3 bucket."
    )
    parser.add_argument(
        "--bucket",
        default=os.environ.get("CORY_PREBUILT_BUCKET")
        or os.environ.get("CORY_PUBLIC_ARTIFACTS_BUCKET")
        or DEFAULT_BUCKET,
        help="Public S3 bucket containing the prebuilt asset archives",
    )
    parser.add_argument(
        "--prefix",
        default=os.environ.get("CORY_PREBUILT_PREFIX", DEFAULT_PREFIX),
        help="S3 prefix containing the asset archives",
    )
    parser.add_argument(
        "--region",
        default=os.environ.get("AWS_REGION", DEFAULT_REGION),
        help="AWS region for the public bucket URL",
    )
    parser.add_argument(
        "--assets",
        nargs="+",
        choices=sorted([*ASSET_ARCHIVES.keys(), "all"]),
        default=["all"],
        help="Asset archives to restore",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    root = repo_root()
    names = list(ASSET_ARCHIVES) if "all" in args.assets else args.assets

    with tempfile.TemporaryDirectory(prefix="cory-prebuilt-assets-") as temp_dir:
        temp_root = Path(temp_dir)
        for name in names:
            archive_name = ASSET_ARCHIVES[name]
            archive_path = temp_root / archive_name
            url = public_asset_url(
                bucket=args.bucket,
                prefix=args.prefix,
                region=args.region,
                archive_name=archive_name,
            )
            download(url, archive_path)
            destination = root / ASSET_TARGETS[name]
            destination.mkdir(parents=True, exist_ok=True)
            safe_extract(archive_path, destination)
            log(f"restored {name} from {url}")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
