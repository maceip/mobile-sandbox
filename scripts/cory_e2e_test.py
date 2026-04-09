#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import os
import re
import shlex
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

PACKAGE = "com.example.orderfiledemo"
ACTIVITY = f"{PACKAGE}/com.example.orderfiledemo.MainActivity"
APK = Path("/home/cory/ndk-samples/Cory/app/build/outputs/apk/debug/app-debug.apk")
BOUNDS_RE = re.compile(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]")


class TestFailure(RuntimeError):
    pass


def run(cmd: list[str], *, check: bool = True, capture: bool = True) -> subprocess.CompletedProcess[str]:
    result = subprocess.run(
        cmd,
        check=False,
        capture_output=capture,
        text=True,
    )
    if check and result.returncode != 0:
        raise TestFailure(
            f"command failed ({result.returncode}): {' '.join(shlex.quote(p) for p in cmd)}\n"
            f"stdout:\n{result.stdout}\n"
            f"stderr:\n{result.stderr}"
        )
    return result


def adb(*args: str, check: bool = True, capture: bool = True) -> subprocess.CompletedProcess[str]:
    base = [os.environ.get("ADB", "adb")]
    serial = os.environ.get("ANDROID_SERIAL")
    if serial:
        base += ["-s", serial]
    return run(base + list(args), check=check, capture=capture)


def shell(command: str, *, check: bool = True) -> str:
    return adb("shell", command, check=check).stdout.replace("\r", "")


def run_as(command: str, *, check: bool = True) -> str:
    return shell(f"run-as {PACKAGE} sh -c {shlex.quote(command)}", check=check)


def dump_ui() -> ET.Element:
    shell("uiautomator dump /data/local/tmp/cory-ui.xml >/dev/null")
    xml_text = adb("exec-out", "cat", "/data/local/tmp/cory-ui.xml").stdout
    return ET.fromstring(xml_text)


def find_node(*, resource_id: str | None = None, text: str | None = None) -> ET.Element:
    root = dump_ui()
    for node in root.iter("node"):
        node_id = node.attrib.get("resource-id", "")
        node_text = node.attrib.get("text", "")
        if resource_id is not None and node_id != resource_id:
            continue
        if text is not None and node_text != text:
            continue
        return node
    raise TestFailure(f"node not found: resource_id={resource_id!r} text={text!r}")


def bounds_center(bounds: str) -> tuple[int, int]:
    match = BOUNDS_RE.fullmatch(bounds)
    if not match:
        raise TestFailure(f"unexpected bounds format: {bounds}")
    x1, y1, x2, y2 = map(int, match.groups())
    return ((x1 + x2) // 2, (y1 + y2) // 2)


def tap_node(*, resource_id: str | None = None, text: str | None = None) -> None:
    node = find_node(resource_id=resource_id, text=text)
    x, y = bounds_center(node.attrib["bounds"])
    shell(f"input tap {x} {y}")
    time.sleep(0.5)


def paste_text(text: str) -> None:
    safe = text.replace("\\", "\\\\").replace('"', '\\"')
    shell(f'cmd clipboard set text "{safe}"')
    shell("input keyevent KEYCODE_PASTE")
    time.sleep(0.5)


def wait_for_path(path: str, *, timeout: float = 20.0) -> None:
    deadline = time.time() + timeout
    while time.time() < deadline:
        result = run_as(f"test -e {shlex.quote(path)} && echo ok", check=False).strip()
        if result == "ok":
            return
        time.sleep(0.5)
    raise TestFailure(f"timed out waiting for path: {path}")


def read_state_json(name: str) -> dict:
    return json.loads(run_as(f"cat files/inceptionsandbox/state/{name}.json"))


def read_state_text(name: str, suffix: str) -> str:
    return run_as(f"cat files/inceptionsandbox/state/{name}.{suffix}")


def launch_app() -> None:
    shell(f"am force-stop {PACKAGE}", check=False)
    adb("shell", "am", "start", "-n", ACTIVITY)
    wait_for_ready_ui()


def wait_for_ready_ui(timeout: float = 30.0) -> None:
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            find_node(resource_id=f"{PACKAGE}:id/command_button")
            find_node(resource_id=f"{PACKAGE}:id/run_button")
            return
        except TestFailure:
            time.sleep(1)
    raise TestFailure("app UI never became ready")


def run_command_via_ui(command: str) -> dict:
    launch_app()
    tap_node(resource_id=f"{PACKAGE}:id/command_input")
    paste_text(command)
    tap_node(resource_id=f"{PACKAGE}:id/command_button")
    deadline = time.time() + 30
    while time.time() < deadline:
        try:
            data = read_state_json("last_command")
            if data.get("command") == command:
                data["stdout"] = read_state_text("last_command", "stdout")
                data["stderr"] = read_state_text("last_command", "stderr")
                return data
        except Exception:
            pass
        time.sleep(0.5)
    raise TestFailure(f"command did not complete: {command}")


def run_current_script_via_ui(file_label: str) -> dict:
    launch_app()
    tap_node(text=file_label)
    tap_node(resource_id=f"{PACKAGE}:id/run_button")
    deadline = time.time() + 30
    while time.time() < deadline:
        try:
            data = read_state_json("last_run")
            if data.get("script", "").endswith(file_label):
                data["stdout"] = read_state_text("last_run", "stdout")
                data["stderr"] = read_state_text("last_run", "stderr")
                return data
        except Exception:
            pass
        time.sleep(0.5)
    raise TestFailure(f"script run did not complete: {file_label}")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--skip-install", action="store_true")
    parser.add_argument("--allow-known-failures", action="store_true")
    args = parser.parse_args()

    if not args.skip_install:
        adb("install", "-r", "-t", str(APK))

    hello_py = "print(\\\"py-ok\\\")\\n"
    util_js = "exports.tag = (value) => `tag:${value}`;\\n"
    hello_js = (
        "const fs = require('node:fs');\\n"
        "const path = require('node:path');\\n"
        "const { tag } = require('./util.js');\\n"
        "const payload = { msg: 'js-ok', nums: [1, 2, 3] };\\n"
        "const json = JSON.stringify(payload);\\n"
        "const parsed = JSON.parse(json);\\n"
        "fs.writeFileSync('node-output.txt', parsed.msg + '\\\\n');\\n"
        "console.log(tag(parsed.msg));\\n"
        "console.log(fs.readFileSync('node-output.txt', 'utf8').trim());\\n"
        "console.log(path.basename(process.cwd()));\\n"
    )
    hello_rs = "fn main(){println!(\\\"rs-ok\\\");}\\n"
    create_files = (
        "python -c "
        + shlex.quote(
            "open('hello.py','w').write({hello_py!r});"
            "open('util.js','w').write({util_js!r});"
            "open('hello.js','w').write({hello_js!r});"
            "open('hello.rs','w').write({hello_rs!r})".format(
                hello_py=hello_py,
                util_js=util_js,
                hello_js=hello_js,
                hello_rs=hello_rs,
            )
        )
    )
    create_result = run_command_via_ui(create_files)
    if create_result["exit_code"] != 0:
        raise TestFailure(f"file creation failed: {create_result}")

    wait_for_path("files/inceptionsandbox/workspace/hello.py")
    wait_for_path("files/inceptionsandbox/workspace/hello.js")
    wait_for_path("files/inceptionsandbox/workspace/util.js")
    wait_for_path("files/inceptionsandbox/workspace/hello.rs")

    py_result = run_current_script_via_ui("hello.py")
    if py_result["exit_code"] != 0 or "py-ok" not in py_result["stdout"]:
        raise TestFailure(f"python run failed: {py_result}")

    js_result = run_command_via_ui("node hello.js")
    wait_for_path("files/inceptionsandbox/workspace/node-output.txt")
    rs_result = run_command_via_ui("rustc hello.rs -o hello-rs")
    git_init = run_command_via_ui("git init")
    git_add = run_command_via_ui("git add")
    git_commit = run_command_via_ui("git commit -m smoke")
    git_worktree_add = run_command_via_ui("git worktree add wt HEAD")
    git_worktree_list = run_command_via_ui("git worktree list")

    failures: list[str] = []
    js_output = js_result["stdout"] + js_result["stderr"]
    if (
        js_result["exit_code"] != 0
        or "tag:js-ok" not in js_output
        or "js-ok" not in js_output
        or "workspace" not in js_output
    ):
        failures.append("js runtime not working yet")
    if rs_result["exit_code"] != 0:
        failures.append("rust compile/run not working yet")
    if git_init["exit_code"] != 0:
        failures.append("git init failed")
    if git_add["exit_code"] != 0:
        failures.append("git add failed")
    if git_commit["exit_code"] != 0:
        failures.append("git commit failed")
    if git_worktree_add["exit_code"] != 0:
        failures.append("git worktree add failed")
    if git_worktree_list["exit_code"] != 0 or "wt" not in git_worktree_list["stdout"]:
        failures.append("git worktree list failed")

    report = {
        "python": py_result,
        "javascript": js_result,
        "rust": rs_result,
        "git": {
            "init": git_init,
            "add": git_add,
            "commit": git_commit,
            "worktree_add": git_worktree_add,
            "worktree_list": git_worktree_list,
        },
        "failures": failures,
    }
    print(json.dumps(report, indent=2))

    if failures and not args.allow_known_failures:
        return 1
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except TestFailure as exc:
        print(str(exc), file=sys.stderr)
        raise SystemExit(1)
