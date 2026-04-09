from __future__ import annotations

import contextlib
import io
import json
import os
import pathlib
import runpy
import sys
import traceback

import cory_process
import cory_sandbox


def _state_dir(paths: cory_sandbox.SandboxPaths | None) -> pathlib.Path | None:
    if paths is not None:
        return paths.state
    state_root = os.environ.get("CORY_SANDBOX_STATE")
    return pathlib.Path(state_root) if state_root else None


def _persist_terminal_state(
    stem: str,
    *,
    paths: cory_sandbox.SandboxPaths | None,
    stdout_text: str,
    stderr_text: str,
    metadata: dict[str, object],
) -> None:
    state_dir = _state_dir(paths)
    if state_dir is None:
        return
    state_dir.mkdir(parents=True, exist_ok=True)
    (state_dir / f"{stem}.stdout").write_text(stdout_text, encoding="utf-8")
    (state_dir / f"{stem}.stderr").write_text(stderr_text, encoding="utf-8")
    (state_dir / f"{stem}.json").write_text(
        json.dumps(metadata, indent=2),
        encoding="utf-8",
    )


def run_script(script_path: str) -> int:
    paths: cory_sandbox.SandboxPaths | None = None
    script = pathlib.Path(script_path).resolve()
    stdout_buffer = io.StringIO()
    stderr_buffer = io.StringIO()
    exit_code = 0

    with contextlib.redirect_stdout(stdout_buffer), contextlib.redirect_stderr(stderr_buffer):
        try:
            paths = cory_sandbox.bootstrap()
            if paths is None:
                raise RuntimeError("Cory sandbox paths are not configured")
            runpy.run_path(str(script), run_name="__main__")
        except SystemExit as exc:
            exit_code = exc.code if isinstance(exc.code, int) else 1
        except Exception:
            exit_code = 1
            traceback.print_exc()

    stdout_text = stdout_buffer.getvalue()
    stderr_text = stderr_buffer.getvalue()
    _persist_terminal_state(
        "last_run",
        paths=paths,
        stdout_text=stdout_text,
        stderr_text=stderr_text,
        metadata={
            "script": str(script),
            "exit_code": exit_code,
            "stdout_bytes": len(stdout_text.encode("utf-8")),
            "stderr_bytes": len(stderr_text.encode("utf-8")),
            "python": sys.version.split()[0],
        },
    )
    return exit_code


def run_command(command: str) -> int:
    paths: cory_sandbox.SandboxPaths | None = None
    stdout_text = ""
    stderr_text = ""
    exit_code = 0

    try:
        paths = cory_sandbox.bootstrap()
        if paths is None:
            raise RuntimeError("Cory sandbox paths are not configured")

        result = cory_process.run(
            command,
            cwd=str(paths.workspace),
            shell=True,
            capture_output=True,
            text=True,
            check=False,
        )
        stdout_text = result.stdout or ""
        stderr_text = result.stderr or ""
        exit_code = result.returncode
    except Exception:
        exit_code = 1
        stderr_text = traceback.format_exc()

    _persist_terminal_state(
        "last_command",
        paths=paths,
        stdout_text=stdout_text,
        stderr_text=stderr_text,
        metadata={
            "command": command,
            "exit_code": exit_code,
            "stdout_bytes": len(stdout_text.encode("utf-8")),
            "stderr_bytes": len(stderr_text.encode("utf-8")),
            "python": sys.version.split()[0],
        },
    )
    return exit_code
