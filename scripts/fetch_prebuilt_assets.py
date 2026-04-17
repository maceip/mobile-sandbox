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


# Required assets — build fails if these cannot be fetched.
ASSET_ARCHIVES = {
    "node": "node24-android.tgz",
    "python": "python-android-prefix.tgz",
    "busybox": "busybox-arm64-v8a.tgz",
    "ripgrep": "ripgrep-arm64-v8a.tgz",
    "rust": "rust-cory_rust-arm64-release.tgz",
    "shell-deps": "shell-deps-arm64-v8a.tgz",
}

# Where each archive lands relative to the repo root (for docs / CLI help only;
# extraction always uses the repo root because tarball members are repo-relative).
ASSET_TARGETS = {
    "node": Path("third_party"),
    "python": Path("third_party/python-android"),
    "busybox": Path("third_party/ndk-busybox-ref/libs"),
    "ripgrep": Path("third_party/ripgrep/target/aarch64-linux-android"),
    "rust": Path("rust/cory_rust/target/aarch64-linux-android"),
    "shell-deps": Path("third_party/shell-deps"),
}

# Optional assets — skipped with a warning if the archive is missing from
# S3. Used for binaries that don't have a producer yet but are wired into
# the build pipeline so they'll be automatically picked up as soon as
# someone uploads the archive.
OPTIONAL_ASSET_ARCHIVES = {
    "git": "git-android.tgz",
}

OPTIONAL_ASSET_TARGETS = {
    "git": Path("third_party/git-android"),
}

DEFAULT_REGION = "eu-central-1"
DEFAULT_BUCKET = "cory-android-ci-manual-publicartifactsbucket-kmzlqxd3q1il"
DEFAULT_PREFIX = "builds/prebuilt/latest"

# Older CodeBuild uploads used this name with a debug/ tree inside the tarball.
RUST_ARCHIVE_LEGACY = "rust-cory_rust-arm64-debug.tgz"


def repo_root() -> Path:
    return Path(__file__).resolve().parent.parent


def log(message: str) -> None:
    print(f"[fetch-prebuilt-assets] {message}")


def repo_relative_member(name: str) -> Path:
    return Path(name.lstrip("/"))


def validate_member_path(destination: Path, relative_path: Path) -> Path:
    destination_resolved = destination.resolve()
    target_path = (destination / relative_path).resolve()
    if destination_resolved not in target_path.parents and target_path != destination_resolved:
        raise FetchError(f"refusing to extract unsafe archive member: {relative_path}")
    return target_path


def rewrite_legacy_python_member_path(member_name: str) -> Path:
    relative = repo_relative_member(member_name)
    if not relative.parts:
        return relative
    if relative.parts[0] != "prefix":
        return relative
    return Path("third_party/python-android") / relative


def public_asset_url(*, bucket: str, prefix: str, region: str, archive_name: str) -> str:
    clean_prefix = prefix.strip("/")
    return f"https://{bucket}.s3.{region}.amazonaws.com/{clean_prefix}/{archive_name}"


def download(url: str, destination: Path, *, optional: bool = False) -> bool:
    """Downloads URL to destination.

    Returns True on success, False if optional and 404'd.
    Raises FetchError on any other failure.
    """
    log(f"download: {url}")
    try:
        with urllib.request.urlopen(url) as response, destination.open("wb") as output:
            shutil.copyfileobj(response, output)
        return True
    except urllib.error.HTTPError as exc:
        if optional and exc.code in (403, 404):
            log(f"optional asset not found (HTTP {exc.code}), skipping: {url}")
            return False
        raise FetchError(f"download failed ({exc.code}): {url}") from exc
    except urllib.error.URLError as exc:
        raise FetchError(f"download failed: {url}: {exc.reason}") from exc


def safe_extract(
    archive_path: Path,
    destination: Path,
    *,
    legacy_prefix_root: Path | None = None,
) -> None:
    """Extract a gzip tarball produced with `tar -C <repo-root> ...`.

    Member paths are repo-relative (e.g. ``third_party/...``), so *destination*
    must be the repository root.
    """
    with tarfile.open(archive_path, "r:gz") as archive:
        for member in archive.getmembers():
            relative_path = repo_relative_member(member.name)
            if legacy_prefix_root and relative_path.parts[:1] == ("prefix",):
                relative_path = legacy_prefix_root / relative_path
            validate_member_path(destination, relative_path)

        if legacy_prefix_root is None:
            archive.extractall(destination)
            return

        for member in archive.getmembers():
            rewritten_path = rewrite_legacy_python_member_path(member.name)
            validate_member_path(destination, rewritten_path)
            if member.isdir():
                (destination / rewritten_path).mkdir(parents=True, exist_ok=True)
                continue
            if member.issym():
                target_path = destination / rewritten_path
                target_path.parent.mkdir(parents=True, exist_ok=True)
                if target_path.exists() or target_path.is_symlink():
                    target_path.unlink()
                target_path.symlink_to(member.linkname)
                continue
            if member.islnk():
                link_target = rewrite_legacy_python_member_path(member.linkname)
                validate_member_path(destination, link_target)
                target_path = destination / rewritten_path
                target_path.parent.mkdir(parents=True, exist_ok=True)
                if target_path.exists() or target_path.is_symlink():
                    target_path.unlink()
                os.link(destination / link_target, target_path)
                continue

            extracted = archive.extractfile(member)
            if extracted is None:
                continue

            target_path = destination / rewritten_path
            target_path.parent.mkdir(parents=True, exist_ok=True)
            with extracted, target_path.open("wb") as output:
                shutil.copyfileobj(extracted, output)

            mode = member.mode & 0o777
            if mode:
                target_path.chmod(mode)


def ensure_rust_release_staticlib_from_debug(root: Path) -> None:
    """Gradle links ``release/libcory_rust.a``; legacy S3 bundles only had ``debug/``."""
    release_a = root / "rust/cory_rust/target/aarch64-linux-android/release/libcory_rust.a"
    debug_a = root / "rust/cory_rust/target/aarch64-linux-android/debug/libcory_rust.a"
    if release_a.is_file():
        return
    if not debug_a.is_file():
        return
    release_a.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(debug_a, release_a)
    log(
        "rust: copied debug/libcory_rust.a -> release/ (legacy prebuilt). "
        f"Upload {ASSET_ARCHIVES['rust']} to S3 when available."
    )


def parse_args() -> argparse.Namespace:
    all_names = sorted([*ASSET_ARCHIVES.keys(), *OPTIONAL_ASSET_ARCHIVES.keys(), "all"])
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
        choices=all_names,
        default=["all"],
        help="Asset archives to restore",
    )
    return parser.parse_args()


def fetch_asset(
    *, name: str, archive_name: str, args: argparse.Namespace,
    temp_root: Path, root: Path, optional: bool
) -> None:
    archive_path = temp_root / archive_name
    url = public_asset_url(
        bucket=args.bucket,
        prefix=args.prefix,
        region=args.region,
        archive_name=archive_name,
    )
    if not download(url, archive_path, optional=optional):
        return  # optional 404, already logged
    if name == "python":
        safe_extract(
            archive_path,
            root,
            legacy_prefix_root=Path("third_party/python-android"),
        )
    else:
        safe_extract(archive_path, root)
    log(f"restored {name} from {url}")


def fetch_rust_prebuilt(*, args: argparse.Namespace, temp_root: Path, root: Path) -> None:
    primary_name = ASSET_ARCHIVES["rust"]
    archive_path = temp_root / primary_name
    url = public_asset_url(
        bucket=args.bucket,
        prefix=args.prefix,
        region=args.region,
        archive_name=primary_name,
    )
    if download(url, archive_path, optional=True):
        safe_extract(archive_path, root)
        ensure_rust_release_staticlib_from_debug(root)
        log(f"restored rust from {url}")
        return
    log(f"rust: {primary_name} not on server (404); trying {RUST_ARCHIVE_LEGACY}")
    legacy_path = temp_root / RUST_ARCHIVE_LEGACY
    url_legacy = public_asset_url(
        bucket=args.bucket,
        prefix=args.prefix,
        region=args.region,
        archive_name=RUST_ARCHIVE_LEGACY,
    )
    download(url_legacy, legacy_path, optional=False)
    safe_extract(legacy_path, root)
    ensure_rust_release_staticlib_from_debug(root)
    log(f"restored rust from legacy bundle {url_legacy}")


def main() -> int:
    args = parse_args()
    root = repo_root()

    requested = args.assets
    if "all" in requested:
        required_names = list(ASSET_ARCHIVES)
        optional_names = list(OPTIONAL_ASSET_ARCHIVES)
    else:
        required_names = [n for n in requested if n in ASSET_ARCHIVES]
        optional_names = [n for n in requested if n in OPTIONAL_ASSET_ARCHIVES]

    with tempfile.TemporaryDirectory(prefix="cory-prebuilt-assets-") as temp_dir:
        temp_root = Path(temp_dir)
        for name in required_names:
            if name == "rust":
                fetch_rust_prebuilt(args=args, temp_root=temp_root, root=root)
            else:
                fetch_asset(
                    name=name,
                    archive_name=ASSET_ARCHIVES[name],
                    args=args,
                    temp_root=temp_root,
                    root=root,
                    optional=False,
                )
        for name in optional_names:
            fetch_asset(
                name=name,
                archive_name=OPTIONAL_ASSET_ARCHIVES[name],
                args=args,
                temp_root=temp_root,
                root=root,
                optional=True,
            )

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
