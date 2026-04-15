#!/usr/bin/env python3
"""
scripts/devicefarm/run.py — AWS Device Farm runner for Cory instrumentation tests.

Uploads the release APK + androidTest APK, schedules INSTRUMENTATION (AndroidJUnit),
polls until the run completes, then checks every device job result is PASSED.

Env vars:
  AWS_REGION              (default us-west-2)
  DEVICEFARM_PROJECT_ARN  (required)
  DEVICEFARM_POOL_ARN     (required)
  APK_PATH                (required — app-release.apk)
  TEST_APK_PATH           (required — app-release-androidTest.apk)

Credentials (boto3 default chain):
  • If AWS_SHARED_CREDENTIALS_FILE is unset, the runner uses the first path that exists among:
    CORY_AWS_CREDENTIALS_FILE (optional override), /home/cory/.aws/credentials (Cory build host),
    then ~/.aws/credentials.
  • Alternatively set AWS_ACCESS_KEY_ID + AWS_SECRET_ACCESS_KEY (and optional AWS_SESSION_TOKEN).
"""

from __future__ import annotations

import os
import sys
import time
from dataclasses import dataclass
from pathlib import Path
from urllib.request import Request, urlopen

try:
    import boto3  # type: ignore
except ImportError:
    sys.stderr.write("ERROR: boto3 not installed. pip install boto3\n")
    sys.exit(2)


POLL_INTERVAL_S = 20
MAX_WAIT_S = 30 * 60

# Cory build machines often keep keys in /home/cory/.aws/credentials (WSL/Linux or shared home).
_BUILD_HOST_SHARED_CREDS = Path("/home/cory/.aws/credentials")


def log(msg: str) -> None:
    sys.stdout.write(f"[df-smoke] {msg}\n")
    sys.stdout.flush()


def configure_default_aws_credentials_file() -> None:
    """If AWS_SHARED_CREDENTIALS_FILE is not set, point boto3 at an existing shared credentials file."""
    if os.environ.get("AWS_SHARED_CREDENTIALS_FILE"):
        return
    candidates: list[Path] = []
    override = os.environ.get("CORY_AWS_CREDENTIALS_FILE")
    if override:
        candidates.append(Path(override))
    candidates.append(_BUILD_HOST_SHARED_CREDS)
    candidates.append(Path.home() / ".aws" / "credentials")

    for path in candidates:
        try:
            if path.is_file():
                os.environ["AWS_SHARED_CREDENTIALS_FILE"] = str(path.resolve())
                log(f"using AWS credentials file: {path}")
                return
        except OSError:
            continue


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
    test_apk_path: Path
    region: str


def load_config() -> Config:
    return Config(
        project_arn=require_env("DEVICEFARM_PROJECT_ARN"),
        pool_arn=require_env("DEVICEFARM_POOL_ARN"),
        apk_path=Path(require_env("APK_PATH")),
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
    """Create an upload slot, PUT the file, poll until SUCCEEDED, return ARN."""
    resp = df.create_upload(projectArn=project_arn, name=name, type=upload_type)
    arn = resp["upload"]["arn"]
    url = resp["upload"]["url"]
    log(f"uploading {name} ({upload_type}) — {path.stat().st_size} bytes")
    _put_to_signed_url(url, path)
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


def schedule_run(df, project_arn: str, pool_arn: str, apk_arn: str, test_apk_arn: str) -> str:
    resp = df.schedule_run(
        projectArn=project_arn,
        appArn=apk_arn,
        devicePoolArn=pool_arn,
        name=f"cory-instrumentation-{int(time.time())}",
        test={
            "type": "INSTRUMENTATION",
            "testPackageArn": test_apk_arn,
        },
        executionConfiguration={
            "jobTimeoutMinutes": 30,
            "videoCapture": False,
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


def summarize_jobs(df, run_arn: str) -> int:
    """Return 0 if every job PASSED, else 1."""
    jobs = df.list_jobs(arn=run_arn)["jobs"]
    if not jobs:
        log("FAIL: no jobs in run")
        return 1

    log("")
    log("=" * 60)
    log("per-device instrumentation results")
    log("=" * 60)
    fails = 0
    for j in jobs:
        device = f"{j['device']['name']} ({j['device']['os']})"
        result = j.get("result", "")
        if result == "PASSED":
            log(f"  PASS  {device}")
        else:
            log(f"  FAIL  {device}  result={result!r} status={j.get('status')!r}")
            fails += 1
            # Pull a few test names / failures if present
            try:
                suites = df.list_suites(arn=j["arn"])["suites"]
                for s in suites:
                    tests = df.list_tests(arn=s["arn"])["tests"]
                    for t in tests[:20]:
                        if t.get("result") not in ("PASSED", None, ""):
                            log(f"        {t.get('name')}: {t.get('result')}")
            except Exception as e:
                log(f"        (could not list tests: {e})")

    log("=" * 60)
    if fails == 0:
        log(f"ALL {len(jobs)} DEVICES PASSED")
        return 0
    log(f"{fails}/{len(jobs)} DEVICES FAILED")
    return 1


def main() -> int:
    configure_default_aws_credentials_file()
    cfg = load_config()

    if not cfg.apk_path.exists():
        sys.stderr.write(f"ERROR: APK not found at {cfg.apk_path}\n")
        return 2
    if not cfg.test_apk_path.exists():
        sys.stderr.write(f"ERROR: test APK not found at {cfg.test_apk_path}\n")
        return 2

    df = boto3.client("devicefarm", region_name=cfg.region)

    log("uploading app + instrumentation test APK to Device Farm")
    apk_arn = upload(
        df, cfg.project_arn, cfg.apk_path, cfg.apk_path.name, "ANDROID_APP"
    )
    test_arn = upload(
        df,
        cfg.project_arn,
        cfg.test_apk_path,
        cfg.test_apk_path.name,
        "INSTRUMENTATION_TEST_PACKAGE",
    )

    log("")
    log("scheduling INSTRUMENTATION run")
    run_arn = schedule_run(df, cfg.project_arn, cfg.pool_arn, apk_arn, test_arn)
    log(f"run ARN: {run_arn}")
    log("  view: https://us-west-2.console.aws.amazon.com/devicefarm/")

    log("")
    log("polling run status")
    run = wait_for_run(df, run_arn)
    log(
        f"run finished: status={run['status']} result={run['result']} "
        f"device_minutes_metered={run['deviceMinutes']['metered']:.2f}"
    )

    return summarize_jobs(df, run_arn)


if __name__ == "__main__":
    sys.exit(main())
