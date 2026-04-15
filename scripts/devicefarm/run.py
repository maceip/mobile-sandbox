#!/usr/bin/env python3
"""
scripts/devicefarm/run.py — end-to-end Device Farm smoke test runner.

Uploads an APK, the `inner-smoke.sh` script bundled as an Appium-Node
test package, and `test-spec.yml`, schedules a run on the project's
"Top Devices" pool, polls until the run finishes, then downloads the
"Test spec output" artifact from the first job and greps it for our
`ALL_TESTS_PASSED` sentinel.

Exit code 0 only if the sentinel is present in the output for all
devices tested — that's the real source of truth, not Device Farm's
UI-level FAILED marker, which is a misfeature of its APPIUM_NODE
result parser (it expects mocha-style stdout patterns and synthesizes
a "failed" result entry when it doesn't see them).

Env vars:
  AWS_ACCESS_KEY_ID       (required)
  AWS_SECRET_ACCESS_KEY   (required)
  AWS_REGION              (default us-west-2 — DF mobile is only there)
  DEVICEFARM_PROJECT_ARN  (required — e.g. arn:aws:devicefarm:us-west-2:<acct>:project:<uuid>)
  DEVICEFARM_POOL_ARN     (required — e.g. "Top Devices" curated pool)
  APK_PATH                (required — path to app-debug.apk)

This script has no dependencies beyond boto3 + stdlib, which CI installs.
"""

from __future__ import annotations

import json
import os
import re
import sys
import tempfile
import time
import zipfile
from dataclasses import dataclass
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

try:
    import boto3  # type: ignore
except ImportError:
    sys.stderr.write("ERROR: boto3 not installed. pip install boto3\n")
    sys.exit(2)


def _http_get_with_retry(url: str, tries: int = 5, timeout: int = 30) -> bytes:
    """GET with exponential backoff for transient S3 5xx / network errors.

    Device Farm's signed S3 URLs sporadically return 503 Service Unavailable
    under load; we've seen it maybe 1 in 10 times. Retry with 1s/2s/4s/8s/16s
    backoff before giving up.
    """
    last_err: Exception | None = None
    for i in range(tries):
        try:
            with urlopen(url, timeout=timeout) as r:
                return r.read()
        except HTTPError as e:
            last_err = e
            if e.code in (429, 500, 502, 503, 504) and i < tries - 1:
                delay = 2 ** i
                sys.stderr.write(
                    f"[df-smoke] HTTP {e.code} on fetch, retry {i + 1}/{tries} in {delay}s\n"
                )
                time.sleep(delay)
                continue
            raise
        except URLError as e:
            last_err = e
            if i < tries - 1:
                delay = 2 ** i
                sys.stderr.write(
                    f"[df-smoke] URL error {e}, retry {i + 1}/{tries} in {delay}s\n"
                )
                time.sleep(delay)
                continue
            raise
    # unreachable
    raise RuntimeError(f"_http_get_with_retry exhausted: {last_err}")


HERE = Path(__file__).resolve().parent
INNER_SH = HERE / "inner-smoke.sh"
TEST_SPEC = HERE / "test-spec.yml"
SENTINEL = "ALL_TESTS_PASSED"
POLL_INTERVAL_S = 20
MAX_WAIT_S = 30 * 60


def log(msg: str) -> None:
    sys.stdout.write(f"[df-smoke] {msg}\n")
    sys.stdout.flush()


def require_env(name: str) -> str:
    v = os.environ.get(name)
    if not v:
        sys.stderr.write(f"ERROR: required env var {name} is not set\n")
        sys.exit(2)
    return v


@dataclass
class Config:
    project_arn: str
    pool_arn: str
    apk_path: Path
    region: str


def load_config() -> Config:
    return Config(
        project_arn=require_env("DEVICEFARM_PROJECT_ARN"),
        pool_arn=require_env("DEVICEFARM_POOL_ARN"),
        apk_path=Path(require_env("APK_PATH")),
        region=os.environ.get("AWS_REGION") or "us-west-2",
    )


def build_test_package() -> Path:
    """Zip inner-smoke.sh plus a minimal Appium-Node-compatible package layout."""
    if not INNER_SH.exists():
        sys.stderr.write(f"ERROR: {INNER_SH} not found\n")
        sys.exit(2)
    tmp = Path(tempfile.mkdtemp(prefix="df-smoke-"))
    zip_path = tmp / "tests.zip"
    with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr(
            "package.json",
            json.dumps(
                {
                    "name": "cory-smoke",
                    "version": "1.0.0",
                    "main": "tests/smoke.js",
                    "scripts": {"test": "node tests/smoke.js"},
                },
                indent=2,
            ),
        )
        z.writestr(
            "tests/smoke.js",
            "// Placeholder: real smoke test runs in Device Farm test spec.\n"
            'console.log("placeholder");\nprocess.exit(0);\n',
        )
        z.write(INNER_SH, "inner-smoke.sh")
    log(f"built test package {zip_path} ({zip_path.stat().st_size} bytes)")
    return zip_path


def _put_to_signed_url(url: str, path: Path) -> None:
    data = path.read_bytes()
    req = Request(
        url,
        data=data,
        method="PUT",
        headers={"Content-Type": "application/octet-stream"},
    )
    with urlopen(req) as resp:
        if resp.status >= 300:
            raise RuntimeError(f"PUT {url!r}: HTTP {resp.status}")


def upload(df, project_arn: str, path: Path, name: str, upload_type: str) -> str:
    """Create an upload slot, PUT the file, poll until SUCCEEDED, return ARN."""
    resp = df.create_upload(projectArn=project_arn, name=name, type=upload_type)
    arn = resp["upload"]["arn"]
    url = resp["upload"]["url"]
    log(f"uploading {name} ({upload_type}) — {path.stat().st_size} bytes")
    _put_to_signed_url(url, path)
    # Wait for validation
    deadline = time.time() + 120
    while time.time() < deadline:
        status = df.get_upload(arn=arn)["upload"]["status"]
        if status == "SUCCEEDED":
            log(f"  {name}: SUCCEEDED")
            return arn
        if status in ("FAILED", "ERRORED"):
            raise RuntimeError(f"{name} upload {status}")
        time.sleep(2)
    raise RuntimeError(f"{name} upload validation timeout")


def schedule_run(
    df, project_arn: str, pool_arn: str, apk_arn: str, test_arn: str, spec_arn: str
) -> str:
    resp = df.schedule_run(
        projectArn=project_arn,
        appArn=apk_arn,
        devicePoolArn=pool_arn,
        name=f"cory-smoke-{int(time.time())}",
        test={
            "type": "APPIUM_NODE",
            "testPackageArn": test_arn,
            "testSpecArn": spec_arn,
        },
        executionConfiguration={
            "jobTimeoutMinutes": 15,
            "videoCapture": False,
        },
    )
    return resp["run"]["arn"]


def wait_for_run(df, run_arn: str) -> dict:
    """Poll until run reaches a terminal state."""
    start = time.time()
    last_done = -1
    while True:
        elapsed = int(time.time() - start)
        if elapsed > MAX_WAIT_S:
            raise RuntimeError(f"run did not complete within {MAX_WAIT_S}s")
        run = df.get_run(arn=run_arn)["run"]
        done = run["completedJobs"]
        total = run["totalJobs"]
        status = run["status"]
        if done != last_done:
            log(f"  [{elapsed:4d}s] status={status} jobs={done}/{total}")
            last_done = done
        if status in ("COMPLETED", "ERRORED", "STOPPED"):
            return run
        time.sleep(POLL_INTERVAL_S)


def fetch_first_job_output(df, run_arn: str) -> tuple[str, dict]:
    """Return (test spec output as string, per-device job summaries)."""
    jobs = df.list_jobs(arn=run_arn)["jobs"]
    if not jobs:
        raise RuntimeError("no jobs in run")

    per_device = {}
    output_text = ""
    for j in jobs:
        device = f"{j['device']['name']} ({j['device']['os']})"
        per_device[device] = {
            "result": j["result"],
            "status": j["status"],
        }
        suites = df.list_suites(arn=j["arn"])["suites"]
        tests_suite = next(
            (s for s in suites if s["name"] == "Tests Suite"), None
        )
        if tests_suite is None:
            continue
        tests = df.list_tests(arn=tests_suite["arn"])["tests"]
        if not tests:
            continue
        artifacts = df.list_artifacts(arn=tests[0]["arn"], type="FILE")["artifacts"]
        tso = next(
            (a for a in artifacts if a["name"] == "Test spec output"), None
        )
        if tso is None:
            continue
        text = _http_get_with_retry(tso["url"]).decode("utf-8", errors="replace")
        per_device[device]["output"] = text
        if not output_text:
            output_text = text

    return output_text, per_device


def summarize(per_device: dict) -> int:
    """Return exit code: 0 if every device saw ALL_TESTS_PASSED, else non-zero."""
    log("")
    log("=" * 60)
    log("per-device smoke-test summary")
    log("=" * 60)
    fails = 0
    for device, info in per_device.items():
        output = info.get("output", "")
        if SENTINEL in output:
            log(f"  PASS  {device}")
        else:
            log(f"  FAIL  {device}")
            fails += 1
            # Dig for the specific failure line
            m = re.search(r"^(FAIL.*?|.*?exit [1-9][0-9]*.*?)$", output, re.MULTILINE)
            if m:
                log(f"        {m.group(1)}")

    log("=" * 60)
    if fails == 0:
        log(f"ALL {len(per_device)} DEVICES PASSED")
        return 0
    log(f"{fails}/{len(per_device)} DEVICES FAILED")
    return 1


def main() -> int:
    cfg = load_config()

    if not cfg.apk_path.exists():
        sys.stderr.write(f"ERROR: APK not found at {cfg.apk_path}\n")
        return 2

    df = boto3.client("devicefarm", region_name=cfg.region)

    test_pkg = build_test_package()

    log("uploading APK + test package + test spec to Device Farm")
    apk_arn = upload(
        df, cfg.project_arn, cfg.apk_path, cfg.apk_path.name, "ANDROID_APP"
    )
    test_arn = upload(
        df, cfg.project_arn, test_pkg, "tests.zip", "APPIUM_NODE_TEST_PACKAGE"
    )
    spec_arn = upload(
        df, cfg.project_arn, TEST_SPEC, "test-spec.yml", "APPIUM_NODE_TEST_SPEC"
    )

    log("")
    log("scheduling run against pool")
    run_arn = schedule_run(df, cfg.project_arn, cfg.pool_arn, apk_arn, test_arn, spec_arn)
    log(f"run ARN: {run_arn}")
    log(f"  view: https://us-west-2.console.aws.amazon.com/devicefarm/")

    log("")
    log("polling run status")
    run = wait_for_run(df, run_arn)
    log(
        f"run finished: status={run['status']} result={run['result']} "
        f"device_minutes_metered={run['deviceMinutes']['metered']:.2f}"
    )

    _, per_device = fetch_first_job_output(df, run_arn)
    return summarize(per_device)


if __name__ == "__main__":
    sys.exit(main())
