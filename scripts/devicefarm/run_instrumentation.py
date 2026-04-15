#!/usr/bin/env python3
"""
scripts/devicefarm/run_instrumentation.py — UI smoke test runner.

Uploads the release APK + the release androidTest APK to AWS Device
Farm, schedules an INSTRUMENTATION run on the project's "Top Devices"
pool, polls until done, and exits non-zero if any device's test
results show failures.

Unlike scripts/devicefarm/run.py (the legacy APPIUM_NODE + shell
script path), this uses Device Farm's native INSTRUMENTATION test
type, which:

  - Runs as the actual app process on the device, via the package
    manager's instrumentation hook (no `run-as` needed; works on
    non-debuggable APKs)
  - Exercises the full Activity / Compose / SurfaceView / PTY chain
    that real users hit, not just the bundled binaries in isolation
  - Lets Device Farm parse JUnit-style results natively — each
    @Test method becomes a Test in the Test Suite, per-device, with
    real pass/fail counters that the API exposes directly. No stdout
    grepping, no synthetic FAILED entries.

Env vars (all required):
  AWS_ACCESS_KEY_ID
  AWS_SECRET_ACCESS_KEY
  AWS_REGION                  (default us-west-2)
  DEVICEFARM_PROJECT_ARN
  DEVICEFARM_POOL_ARN
  APP_APK_PATH                path to app-release.apk
  TEST_APK_PATH               path to app-release-androidTest.apk
"""

from __future__ import annotations

import os
import sys
import time
from dataclasses import dataclass
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

try:
    import boto3  # type: ignore
except ImportError:
    sys.stderr.write("ERROR: boto3 not installed. pip install boto3\n")
    sys.exit(2)


POLL_INTERVAL_S = 20
MAX_WAIT_S = 30 * 60


def log(msg: str) -> None:
    sys.stdout.write(f"[df-ui] {msg}\n")
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
    app_apk_path: Path
    test_apk_path: Path
    region: str


def load_config() -> Config:
    return Config(
        project_arn=require_env("DEVICEFARM_PROJECT_ARN"),
        pool_arn=require_env("DEVICEFARM_POOL_ARN"),
        app_apk_path=Path(require_env("APP_APK_PATH")),
        test_apk_path=Path(require_env("TEST_APK_PATH")),
        region=os.environ.get("AWS_REGION") or "us-west-2",
    )


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
    resp = df.create_upload(projectArn=project_arn, name=name, type=upload_type)
    arn = resp["upload"]["arn"]
    url = resp["upload"]["url"]
    log(f"uploading {name} ({upload_type}) — {path.stat().st_size:,} bytes")
    _put_to_signed_url(url, path)
    deadline = time.time() + 300  # test APKs validate slowly sometimes
    last_status = None
    while time.time() < deadline:
        u = df.get_upload(arn=arn)["upload"]
        status = u["status"]
        if status != last_status:
            log(f"  {name}: {status}")
            last_status = status
        if status == "SUCCEEDED":
            return arn
        if status in ("FAILED", "ERRORED"):
            msg = u.get("message") or u.get("metadata") or ""
            raise RuntimeError(f"{name} upload {status}: {msg}")
        time.sleep(2)
    raise RuntimeError(f"{name} upload validation timeout")


def schedule_run(
    df,
    project_arn: str,
    pool_arn: str,
    app_arn: str,
    test_arn: str,
) -> str:
    resp = df.schedule_run(
        projectArn=project_arn,
        appArn=app_arn,
        devicePoolArn=pool_arn,
        name=f"cory-ui-smoke-{int(time.time())}",
        test={
            "type": "INSTRUMENTATION",
            "testPackageArn": test_arn,
            # No `filter` — run all @Test methods in the test APK.
            # Right now there's just TerminalUiSmokeTest in
            # app/src/androidTest/, but as we add more @Test methods
            # they'll all run automatically.
        },
        executionConfiguration={
            "jobTimeoutMinutes": 15,
            "videoCapture": True,  # videos help diagnose UI failures
        },
    )
    return resp["run"]["arn"]


def wait_for_run(df, run_arn: str) -> dict:
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


def summarize(df, run_arn: str) -> int:
    """
    For INSTRUMENTATION runs Device Farm exposes per-test results
    natively. We list jobs (per device) → suites (typically one
    "Tests Suite" per job) → tests (one per @Test method), and
    report pass/fail per device + per test.
    """
    jobs = df.list_jobs(arn=run_arn)["jobs"]
    log("")
    log("=" * 60)
    log("per-device UI smoke test results")
    log("=" * 60)

    total_devices = len(jobs)
    failed_devices = 0

    for j in jobs:
        device = f"{j['device']['name']} ({j['device']['os']})"
        result = j["result"]
        counters = j["counters"]
        prefix = "PASS " if result == "PASSED" else "FAIL "
        log(
            f"{prefix} {device}  "
            f"{counters['passed']} passed, "
            f"{counters['failed']} failed, "
            f"{counters['errored']} errored"
        )

        if result != "PASSED":
            failed_devices += 1
            # Walk the suites/tests to surface specific failed test names
            try:
                suites = df.list_suites(arn=j["arn"])["suites"]
                for s in suites:
                    if s["result"] in ("PASSED", "SKIPPED"):
                        continue
                    tests = df.list_tests(arn=s["arn"])["tests"]
                    for t in tests:
                        if t["result"] != "PASSED":
                            log(
                                f"        - {t['name']}: {t['result']} "
                                f"({t.get('message', '')})"
                            )
            except Exception as e:  # noqa: BLE001
                log(f"        (failed to fetch test details: {e})")

    log("=" * 60)
    if failed_devices == 0:
        log(f"ALL {total_devices} DEVICES PASSED")
        return 0
    log(f"{failed_devices}/{total_devices} DEVICES FAILED")
    return 1


def main() -> int:
    cfg = load_config()

    for label, path in (("app", cfg.app_apk_path), ("test", cfg.test_apk_path)):
        if not path.exists():
            sys.stderr.write(f"ERROR: {label} APK not found at {path}\n")
            return 2

    df = boto3.client("devicefarm", region_name=cfg.region)

    log("uploading app + test APKs to Device Farm")
    app_arn = upload(
        df, cfg.project_arn, cfg.app_apk_path, cfg.app_apk_path.name, "ANDROID_APP"
    )
    test_arn = upload(
        df,
        cfg.project_arn,
        cfg.test_apk_path,
        cfg.test_apk_path.name,
        "INSTRUMENTATION_TEST_PACKAGE",
    )

    log("")
    log("scheduling INSTRUMENTATION run against pool")
    run_arn = schedule_run(df, cfg.project_arn, cfg.pool_arn, app_arn, test_arn)
    log(f"run ARN: {run_arn}")
    log("  view: https://us-west-2.console.aws.amazon.com/devicefarm/")

    log("")
    log("polling run status (UI smoke can take 3-5 min per device)")
    run = wait_for_run(df, run_arn)
    log(
        f"run finished: status={run['status']} result={run['result']} "
        f"device_minutes_metered={run['deviceMinutes']['metered']:.2f}"
    )

    return summarize(df, run_arn)


if __name__ == "__main__":
    sys.exit(main())
