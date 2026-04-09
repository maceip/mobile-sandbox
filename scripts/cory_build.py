#!/usr/bin/env python3
from __future__ import annotations

import argparse
import os
import shutil
import subprocess
import sys
import tarfile
import tempfile
from dataclasses import dataclass
from pathlib import Path


class BuildError(RuntimeError):
    pass


@dataclass(frozen=True)
class RemoteNodeConfig:
    host: str = "cory@ec2-3-120-153-36.eu-central-1.compute.amazonaws.com"
    repo_dir: str = "/home/cory/Cory"
    rebuild_script: str = "/home/cory/Cory/codebuild/node/ec2-rebuild-node24-android.sh"
    rebuild_dir: str = "/home/cory/node24-android-rebuild"
    packaged_bundle_dir: str = "/home/cory/Cory/third_party/node24-android"


class CoryBuild:
    def __init__(self, repo_root: Path, remote: RemoteNodeConfig) -> None:
        self.repo_root = repo_root
        self.remote = remote
        self.node_bundle_dir = self.repo_root / "third_party" / "node24-android"
        self.node_bin = self.node_bundle_dir / "bin" / "arm64-v8a" / "node"
        self.node_lib = self.node_bundle_dir / "lib" / "arm64-v8a" / "liblibnode.a"
        self.python_lib = (
            self.repo_root
            / "third_party"
            / "python-android"
            / "prefix"
            / "lib"
            / "libpython3.14.so"
        )
        self.ripgrep_bin = (
            self.repo_root
            / "third_party"
            / "ripgrep"
            / "target"
            / "aarch64-linux-android"
            / "release"
            / "rg"
        )
        self.gradlew = (
            self.repo_root / ("gradlew.bat" if os.name == "nt" else "gradlew")
        )

    def log(self, message: str) -> None:
        print(f"[cory-build] {message}")

    def run(
        self,
        command: list[str],
        *,
        cwd: Path | None = None,
        check: bool = True,
    ) -> subprocess.CompletedProcess[str]:
        self.log("exec: " + " ".join(command))
        result = subprocess.run(
            command,
            cwd=str(cwd) if cwd else None,
            check=False,
            text=True,
        )
        if check and result.returncode != 0:
            raise BuildError(
                f"command failed ({result.returncode}): {' '.join(command)}"
            )
        return result

    def run_capture(
        self,
        command: list[str],
        *,
        cwd: Path | None = None,
        check: bool = True,
    ) -> subprocess.CompletedProcess[str]:
        self.log("exec: " + " ".join(command))
        result = subprocess.run(
            command,
            cwd=str(cwd) if cwd else None,
            check=False,
            text=True,
            capture_output=True,
        )
        if check and result.returncode != 0:
            raise BuildError(
                f"command failed ({result.returncode}): {' '.join(command)}\n"
                f"stdout:\n{result.stdout}\n"
                f"stderr:\n{result.stderr}"
            )
        return result

    def require_tool(self, name: str) -> str:
        path = shutil.which(name)
        if not path:
            raise BuildError(f"required tool not found on PATH: {name}")
        return path

    def doctor(self) -> None:
        self.log("checking local and remote build prerequisites")
        for tool in ("ssh", "python"):
            self.require_tool(tool)
        if os.name == "nt":
            self.require_tool("tar")

        required_paths = [
            self.repo_root / "app" / "build.gradle",
            self.repo_root / "app" / "src" / "main" / "cpp" / "CMakeLists.txt",
            self.python_lib,
            self.node_bin,
            self.node_lib,
            self.repo_root / "third_party" / "libgit2",
            self.repo_root / "rust" / "cory_rust" / "Cargo.toml",
            self.gradlew,
        ]
        missing = [path for path in required_paths if not path.exists()]
        if missing:
            raise BuildError(
                "missing required local paths:\n"
                + "\n".join(f"- {path}" for path in missing)
            )

        if not self.ripgrep_bin.exists():
            self.log(
                "warning: Android ripgrep binary is missing; the app can still build,"
                " but `rg` will not be bundled"
            )

        identity = self.run_capture(
            ["ssh", self.remote.host, "aws", "sts", "get-caller-identity"],
        )
        self.log("remote AWS identity OK")
        print(identity.stdout.strip())

    def rebuild_node_on_ec2(self) -> None:
        self.log("rebuilding Android Node bundle on EC2")
        self.run(
            [
                "ssh",
                self.remote.host,
                "bash",
                "-lc",
                f"cd {self.remote.repo_dir}/codebuild/node && ./ec2-rebuild-node24-android.sh",
            ]
        )
        self.log("promoting rebuilt node artifacts into the packaged EC2 bundle")
        self.run(
            [
                "ssh",
                self.remote.host,
                "bash",
                "-lc",
                (
                    "set -euo pipefail; "
                    f"install -D -m 0755 {self.remote.rebuild_dir}/node "
                    f"{self.remote.packaged_bundle_dir}/bin/arm64-v8a/node; "
                    f"install -D -m 0644 {self.remote.rebuild_dir}/liblibnode.a "
                    f"{self.remote.packaged_bundle_dir}/lib/arm64-v8a/liblibnode.a"
                ),
            ]
        )

    def sync_node_bundle_from_ec2(self) -> None:
        self.log("syncing packaged node24-android bundle from EC2")
        self.require_tool("ssh")
        with tempfile.TemporaryDirectory(prefix="cory-node-bundle-") as temp_dir:
            archive_path = Path(temp_dir) / "node24-android.tar.gz"
            with archive_path.open("wb") as output:
                proc = subprocess.Popen(
                    [
                        "ssh",
                        self.remote.host,
                        "tar",
                        "-C",
                        f"{self.remote.repo_dir}/third_party",
                        "-czf",
                        "-",
                        "node24-android",
                    ],
                    stdout=output,
                )
                return_code = proc.wait()
            if return_code != 0:
                raise BuildError("failed to stream node24-android bundle from EC2")

            parent = self.node_bundle_dir.parent
            parent.mkdir(parents=True, exist_ok=True)
            if self.node_bundle_dir.exists():
                shutil.rmtree(self.node_bundle_dir)
            with tarfile.open(archive_path, "r:gz") as archive:
                archive.extractall(parent)

        if not self.node_bin.exists() or not self.node_lib.exists():
            raise BuildError("synced node24-android bundle is incomplete")

    def build_app(self) -> None:
        self.log("assembling Android app")
        if os.name == "nt":
            command = [str(self.gradlew), ":app:assembleDebug", "--console=plain"]
        else:
            command = ["./gradlew", ":app:assembleDebug", "--console=plain"]
        self.run(command, cwd=self.repo_root)

    def full(self, *, rebuild_node_ec2: bool, sync_node_ec2: bool) -> None:
        self.doctor()
        if rebuild_node_ec2:
            self.rebuild_node_on_ec2()
            sync_node_ec2 = True
        if sync_node_ec2:
            self.sync_node_bundle_from_ec2()
        self.build_app()


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Single entry point for Cory dependency and app builds."
    )
    subparsers = parser.add_subparsers(dest="command", required=True)

    subparsers.add_parser("doctor", help="Validate local and remote prerequisites")
    subparsers.add_parser(
        "sync-node-ec2",
        help="Sync the packaged node24-android bundle from the EC2 source of truth",
    )
    subparsers.add_parser(
        "rebuild-node-ec2",
        help="Rebuild Android Node on EC2 and promote it into the packaged bundle there",
    )
    subparsers.add_parser("build-app", help="Assemble the Android app locally")

    full = subparsers.add_parser(
        "full",
        help="Validate, optionally refresh Node from EC2, and assemble the app",
    )
    full.add_argument(
        "--rebuild-node-ec2",
        action="store_true",
        help="Run the authoritative Android Node rebuild on EC2 before syncing",
    )
    full.add_argument(
        "--sync-node-ec2",
        action="store_true",
        help="Sync the packaged EC2 node24-android bundle before app build",
    )
    return parser


def main() -> int:
    parser = build_parser()
    args = parser.parse_args()

    repo_root = Path(__file__).resolve().parent.parent
    builder = CoryBuild(repo_root=repo_root, remote=RemoteNodeConfig())

    try:
        if args.command == "doctor":
            builder.doctor()
        elif args.command == "sync-node-ec2":
            builder.sync_node_bundle_from_ec2()
        elif args.command == "rebuild-node-ec2":
            builder.rebuild_node_on_ec2()
        elif args.command == "build-app":
            builder.build_app()
        elif args.command == "full":
            builder.full(
                rebuild_node_ec2=args.rebuild_node_ec2,
                sync_node_ec2=args.sync_node_ec2,
            )
        else:
            parser.error(f"unsupported command: {args.command}")
    except BuildError as exc:
        print(str(exc), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

