#!/usr/bin/env python3
"""Pick a Device Farm device ARN: newest Android by numeric OS version."""

from __future__ import annotations

import json
import os
import subprocess
import sys


def run_cli(args: list[str]) -> dict:
    env = dict(os.environ)
    proc = subprocess.run(args, check=True, capture_output=True, text=True, env=env)
    return json.loads(proc.stdout or "{}")


def os_key(version: str) -> tuple[int, ...]:
    parts: list[int] = []
    for token in version.replace("_", ".").split("."):
        if token.isdigit():
            parts.append(int(token))
        else:
            digits = "".join(c for c in token if c.isdigit())
            if digits:
                parts.append(int(digits))
            else:
                parts.append(0)
    return tuple(parts) if parts else (0,)


def main() -> int:
    region = os.environ.get("AWS_DEFAULT_REGION_DEVICE_FARM", "us-west-2")
    token: str | None = None
    devices: list[dict] = []
    while True:
        args = [
            "aws",
            "devicefarm",
            "list-devices",
            "--region",
            region,
            "--output",
            "json",
        ]
        if token:
            args.extend(["--next-token", token])
        page = run_cli(args)
        devices.extend(page.get("devices") or [])
        token = page.get("nextToken")
        if not token:
            break

    android = [d for d in devices if (d.get("platform") or "").upper() == "ANDROID"]
    if not android:
        print("devicefarm: no ANDROID devices returned by list-devices", file=sys.stderr)
        return 2

    android.sort(key=lambda d: os_key(str(d.get("os") or "0")), reverse=True)
    chosen = android[0]
    print(chosen["arn"])
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
