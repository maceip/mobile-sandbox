from __future__ import annotations

import contextlib
import io
import os
import pathlib
import re
import runpy
import shlex
import shutil
from typing import Iterable

import cory_native

try:
    import subprocess as _stdlib_subprocess
except ModuleNotFoundError:
    _stdlib_subprocess = None


_INSTALLED = False
_ORIGINAL_RUN = _stdlib_subprocess.run if _stdlib_subprocess is not None else None
_ORIGINAL_POPEN = _stdlib_subprocess.Popen if _stdlib_subprocess is not None else None
_BUSYBOX_APPLETS: set[str] | None = None
PIPE = getattr(_stdlib_subprocess, "PIPE", object())
DEVNULL = getattr(_stdlib_subprocess, "DEVNULL", object())


class CalledProcessError(RuntimeError):
    def __init__(self, returncode, cmd, output=None, stderr=None):
        super().__init__(f"Command {cmd!r} returned non-zero exit status {returncode}.")
        self.returncode = returncode
        self.cmd = cmd
        self.output = output
        self.stderr = stderr


class CompletedProcess:
    def __init__(self, args, returncode, stdout=None, stderr=None):
        self.args = args
        self.returncode = returncode
        self.stdout = stdout
        self.stderr = stderr


def _as_argv(command, shell: bool) -> list[str]:
    if shell:
        if not isinstance(command, str):
            raise TypeError("shell=True requires a string command")
        return shlex.split(command)
    if isinstance(command, str):
        return shlex.split(command)
    return [str(part) for part in command]


def _resolve_path(value: str, cwd: str | None) -> pathlib.Path:
    path = pathlib.Path(value)
    if path.is_absolute():
        return path
    base = pathlib.Path(cwd or os.getcwd())
    return (base / path).resolve()


def _command_base(env: dict[str, str]) -> pathlib.Path:
    path_value = env.get("CORY_SANDBOX_BIN", env.get("PATH", ".").split(":")[0] or ".")
    return pathlib.Path(path_value)


def _tool_path(name: str, env: dict[str, str]) -> pathlib.Path:
    return _command_base(env) / name


def _tool_exists(name: str, env: dict[str, str]) -> bool:
    path = _tool_path(name, env)
    return path.exists() and os.access(path, os.X_OK)


def _run_external(argv: list[str], cwd: str | None, env: dict[str, str]) -> tuple[int, str, str]:
    if _ORIGINAL_RUN is not None:
        try:
            result = _ORIGINAL_RUN(
                argv,
                cwd=cwd,
                env=env,
                capture_output=True,
                text=True,
                check=False,
            )
        except OSError as exc:
            tool = pathlib.Path(argv[0]).name if argv else "command"
            return 126, "", f"{tool}: {exc}\n"
        return result.returncode, result.stdout, result.stderr

    if not hasattr(os, "posix_spawn"):
        tool = pathlib.Path(argv[0]).name if argv else "command"
        return 126, "", f"{tool}: external execution unavailable in Cory sandbox\n"

    stdout_r, stdout_w = os.pipe()
    stderr_r, stderr_w = os.pipe()
    previous_cwd = None
    try:
        if cwd is not None:
            previous_cwd = os.getcwd()
            os.chdir(cwd)
        pid = os.posix_spawn(
            argv[0],
            argv,
            env,
            file_actions=[
                (os.POSIX_SPAWN_DUP2, stdout_w, 1),
                (os.POSIX_SPAWN_DUP2, stderr_w, 2),
                (os.POSIX_SPAWN_CLOSE, stdout_r),
                (os.POSIX_SPAWN_CLOSE, stderr_r),
                (os.POSIX_SPAWN_CLOSE, stdout_w),
                (os.POSIX_SPAWN_CLOSE, stderr_w),
            ],
        )
    except OSError as exc:
        for fd in (stdout_r, stdout_w, stderr_r, stderr_w):
            try:
                os.close(fd)
            except OSError:
                pass
        if previous_cwd is not None:
            os.chdir(previous_cwd)
        tool = pathlib.Path(argv[0]).name if argv else "command"
        return 126, "", f"{tool}: {exc}\n"

    if previous_cwd is not None:
        os.chdir(previous_cwd)
    os.close(stdout_w)
    os.close(stderr_w)

    stdout_chunks: list[bytes] = []
    stderr_chunks: list[bytes] = []
    for fd, target in ((stdout_r, stdout_chunks), (stderr_r, stderr_chunks)):
        while True:
            chunk = os.read(fd, 65536)
            if not chunk:
                break
            target.append(chunk)
        os.close(fd)

    _, wait_status = os.waitpid(pid, 0)
    return (
        os.waitstatus_to_exitcode(wait_status),
        b"".join(stdout_chunks).decode("utf-8", errors="replace"),
        b"".join(stderr_chunks).decode("utf-8", errors="replace"),
    )


def _busybox_applets(env: dict[str, str]) -> set[str]:
    global _BUSYBOX_APPLETS
    if _BUSYBOX_APPLETS is not None:
        return _BUSYBOX_APPLETS
    if not _tool_exists("busybox", env):
        _BUSYBOX_APPLETS = set()
        return _BUSYBOX_APPLETS
    code, out, _ = _run_external([str(_tool_path("busybox", env)), "--list"], None, env)
    _BUSYBOX_APPLETS = set(out.split()) if code == 0 else set()
    return _BUSYBOX_APPLETS


def _write_placeholder_command(command: str, env: dict[str, str]) -> None:
    base = _command_base(env)
    base.mkdir(parents=True, exist_ok=True)
    placeholder = base / command
    if not placeholder.exists():
        placeholder.write_text(f"# Cory sandbox command placeholder for {command}\n", encoding="utf-8")


def _handle_echo(argv: list[str], _: str | None, __: dict[str, str]) -> tuple[int, str, str]:
    return 0, " ".join(argv[1:]) + ("\n" if len(argv) > 1 else ""), ""


def _handle_pwd(_: list[str], cwd: str | None, __: dict[str, str]) -> tuple[int, str, str]:
    return 0, str(pathlib.Path(cwd or os.getcwd())) + "\n", ""


def _handle_env(_: list[str], __: str | None, env: dict[str, str]) -> tuple[int, str, str]:
    lines = [f"{key}={env[key]}" for key in sorted(env)]
    return 0, "\n".join(lines) + ("\n" if lines else ""), ""


def _handle_ls(argv: list[str], cwd: str | None, __: dict[str, str]) -> tuple[int, str, str]:
    targets = [part for part in argv[1:] if not part.startswith("-")] or ["."]
    output = []
    for target in targets:
        path = _resolve_path(target, cwd)
        if not path.exists():
            return 1, "", f"ls: {target}: No such file or directory\n"
        names = sorted(item.name for item in path.iterdir()) if path.is_dir() else [path.name]
        output.extend(names)
    return 0, "\n".join(output) + ("\n" if output else ""), ""


def _handle_cat(argv: list[str], cwd: str | None, __: dict[str, str]) -> tuple[int, str, str]:
    if len(argv) < 2:
        return 1, "", "cat: missing operand\n"
    chunks = []
    for target in argv[1:]:
        path = _resolve_path(target, cwd)
        if not path.exists():
            return 1, "", f"cat: {target}: No such file or directory\n"
        chunks.append(path.read_text(encoding="utf-8"))
    return 0, "".join(chunks), ""


def _handle_touch(argv: list[str], cwd: str | None, __: dict[str, str]) -> tuple[int, str, str]:
    if len(argv) < 2:
        return 1, "", "touch: missing file operand\n"
    for target in argv[1:]:
        path = _resolve_path(target, cwd)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.touch(exist_ok=True)
    return 0, "", ""


def _handle_mkdir(argv: list[str], cwd: str | None, __: dict[str, str]) -> tuple[int, str, str]:
    if len(argv) < 2:
        return 1, "", "mkdir: missing operand\n"
    recursive = "-p" in argv[1:]
    for target in [part for part in argv[1:] if not part.startswith("-")]:
        path = _resolve_path(target, cwd)
        path.mkdir(parents=recursive, exist_ok=recursive)
    return 0, "", ""


def _handle_rm(argv: list[str], cwd: str | None, __: dict[str, str]) -> tuple[int, str, str]:
    if len(argv) < 2:
        return 1, "", "rm: missing operand\n"
    recursive = any(flag in argv[1:] for flag in ("-r", "-rf", "-fr"))
    force = any(flag in argv[1:] for flag in ("-f", "-rf", "-fr"))
    for target in [part for part in argv[1:] if not part.startswith("-")]:
        path = _resolve_path(target, cwd)
        if not path.exists():
            if force:
                continue
            return 1, "", f"rm: cannot remove '{target}': No such file or directory\n"
        if path.is_dir():
            if not recursive:
                return 1, "", f"rm: cannot remove '{target}': Is a directory\n"
            shutil.rmtree(path)
        else:
            path.unlink()
    return 0, "", ""


def _handle_cp(argv: list[str], cwd: str | None, __: dict[str, str]) -> tuple[int, str, str]:
    if len(argv) < 3:
        return 1, "", "cp: missing file operand\n"
    recursive = "-r" in argv[1:] or "-R" in argv[1:]
    args = [part for part in argv[1:] if not part.startswith("-")]
    src = _resolve_path(args[0], cwd)
    dst = _resolve_path(args[1], cwd)
    if src.is_dir():
        if not recursive:
            return 1, "", f"cp: -r not specified; omitting directory '{args[0]}'\n"
        shutil.copytree(src, dst, dirs_exist_ok=True)
    else:
        dst.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(src, dst)
    return 0, "", ""


def _handle_mv(argv: list[str], cwd: str | None, __: dict[str, str]) -> tuple[int, str, str]:
    if len(argv) < 3:
        return 1, "", "mv: missing file operand\n"
    src = _resolve_path(argv[1], cwd)
    dst = _resolve_path(argv[2], cwd)
    dst.parent.mkdir(parents=True, exist_ok=True)
    shutil.move(str(src), str(dst))
    return 0, "", ""


def _handle_find(argv: list[str], cwd: str | None, __: dict[str, str]) -> tuple[int, str, str]:
    root = _resolve_path(argv[1], cwd) if len(argv) > 1 and not argv[1].startswith("-") else _resolve_path(".", cwd)
    matches = [str(root)]
    for path in sorted(root.rglob("*")):
        matches.append(str(path))
    return 0, "\n".join(matches) + "\n", ""


def _handle_head(argv: list[str], cwd: str | None, __: dict[str, str]) -> tuple[int, str, str]:
    if len(argv) < 2:
        return 1, "", "head: missing file operand\n"
    count = 10
    paths = []
    index = 1
    while index < len(argv):
        token = argv[index]
        if token == "-n" and index + 1 < len(argv):
            count = int(argv[index + 1])
            index += 2
            continue
        if not token.startswith("-"):
            paths.append(token)
        index += 1
    if not paths:
        return 1, "", "head: missing file operand\n"
    lines = _resolve_path(paths[0], cwd).read_text(encoding="utf-8").splitlines()
    output = "\n".join(lines[:count])
    return 0, output + ("\n" if output else ""), ""


def _handle_tail(argv: list[str], cwd: str | None, __: dict[str, str]) -> tuple[int, str, str]:
    if len(argv) < 2:
        return 1, "", "tail: missing file operand\n"
    count = 10
    paths = []
    index = 1
    while index < len(argv):
        token = argv[index]
        if token == "-n" and index + 1 < len(argv):
            count = int(argv[index + 1])
            index += 2
            continue
        if not token.startswith("-"):
            paths.append(token)
        index += 1
    if not paths:
        return 1, "", "tail: missing file operand\n"
    lines = _resolve_path(paths[0], cwd).read_text(encoding="utf-8").splitlines()
    output = "\n".join(lines[-count:])
    return 0, output + ("\n" if output else ""), ""


def _handle_wc(argv: list[str], cwd: str | None, __: dict[str, str]) -> tuple[int, str, str]:
    if len(argv) < 2:
        return 1, "", "wc: missing file operand\n"
    path = _resolve_path(argv[1], cwd)
    text = path.read_text(encoding="utf-8")
    lines = len(text.splitlines())
    words = len(text.split())
    chars = len(text)
    return 0, f"{lines} {words} {chars} {argv[1]}\n", ""


def _handle_which(argv: list[str], __: str | None, env: dict[str, str]) -> tuple[int, str, str]:
    if len(argv) != 2:
        return 1, "", "which: expected exactly one argument\n"
    command = argv[1]
    direct_tool = _tool_path(command, env)
    if _tool_exists(command, env):
        return 0, str(direct_tool) + "\n", ""
    if command in _COMMANDS:
        _write_placeholder_command(command, env)
        return 0, str(_command_base(env) / command) + "\n", ""
    if command in _busybox_applets(env):
        return 0, str(_tool_path("busybox", env)) + "\n", ""
    return 1, "", ""


def _handle_uname(argv: list[str], _: str | None, __: dict[str, str]) -> tuple[int, str, str]:
    machine = "aarch64"
    if len(argv) > 1 and argv[1] == "-a":
        return 0, f"Android Cory 0.1.0 android {machine}\n", ""
    return 0, "Android\n", ""


def _handle_true(_: list[str], __: str | None, ___: dict[str, str]) -> tuple[int, str, str]:
    return 0, "", ""


def _handle_false(_: list[str], __: str | None, ___: dict[str, str]) -> tuple[int, str, str]:
    return 1, "", ""


def _handle_python(argv: list[str], cwd: str | None, __: dict[str, str]) -> tuple[int, str, str]:
    if len(argv) < 2:
        return 0, "", ""
    target = argv[1]
    if target == "-c" and len(argv) >= 3:
        source = argv[2]
        stdout = io.StringIO()
        stderr = io.StringIO()
        globals_dict = {"__name__": "__main__"}
        with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
            try:
                exec(compile(source, "<cory-shell>", "exec"), globals_dict, globals_dict)
            except SystemExit as exc:
                code = exc.code if isinstance(exc.code, int) else 1
                return code, stdout.getvalue(), stderr.getvalue()
        return 0, stdout.getvalue(), stderr.getvalue()

    path = _resolve_path(target, cwd)
    stdout = io.StringIO()
    stderr = io.StringIO()
    with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
        try:
            runpy.run_path(str(path), run_name="__main__")
        except SystemExit as exc:
            code = exc.code if isinstance(exc.code, int) else 1
            return code, stdout.getvalue(), stderr.getvalue()
    return 0, stdout.getvalue(), stderr.getvalue()


def _handle_node(argv: list[str], cwd: str | None, env: dict[str, str]) -> tuple[int, str, str]:
    if _tool_exists("node", env):
        return _run_external([str(_tool_path("node", env)), *argv[1:]], cwd, env)
    return 127, "", "node: command not found in Cory sandbox\n"


def _handle_rg(argv: list[str], cwd: str | None, env: dict[str, str]) -> tuple[int, str, str]:
    if _tool_exists("rg", env):
        return _run_external([str(_tool_path("rg", env)), *argv[1:]], cwd, env)

    args = [part for part in argv[1:] if not part.startswith("-")]
    if not args:
        return 2, "", "rg: missing pattern\n"
    pattern = args[0]
    targets = args[1:] or ["."]
    try:
        compiled = re.compile(pattern)
    except re.error as exc:
        return 2, "", f"rg: invalid regex: {exc}\n"

    matches: list[str] = []
    for target in targets:
        root = _resolve_path(target, cwd)
        paths: Iterable[pathlib.Path]
        if root.is_dir():
            paths = sorted(path for path in root.rglob("*") if path.is_file())
        else:
            paths = [root]
        for path in paths:
            try:
                text = path.read_text(encoding="utf-8")
            except (UnicodeDecodeError, OSError):
                continue
            for line_number, line in enumerate(text.splitlines(), start=1):
                if compiled.search(line):
                    matches.append(f"{path}:{line_number}:{line}")
    return (0 if matches else 1), ("\n".join(matches) + ("\n" if matches else "")), ""


def _handle_git(argv: list[str], cwd: str | None, __: dict[str, str]) -> tuple[int, str, str]:
    repo_path = str(_resolve_path(".", cwd))
    if len(argv) == 1:
        return 0, "Cory git wrapper: clone init status add commit log checkout worktree version\n", ""

    subcommand = argv[1]
    try:
        if subcommand == "version":
            return 0, f"git via libgit2 {cory_native.git_version()}\n", ""
        if subcommand == "clone":
            if len(argv) < 4:
                return 2, "", "git clone: expected SOURCE and DEST\n"
            cory_native.git_clone(argv[2], str(_resolve_path(argv[3], cwd)))
            return 0, f"Cloned {argv[2]} into {argv[3]}\n", ""
        if subcommand == "init":
            target = repo_path if len(argv) == 2 else str(_resolve_path(argv[2], cwd))
            cory_native.git_init(target)
            return 0, f"Initialized empty Git repository in {target}\n", ""
        if subcommand == "status":
            output = cory_native.git_status_short(repo_path)
            branch_text = "## HEAD (sandbox)\n"
            return 0, branch_text + output, ""
        if subcommand == "add":
            cory_native.git_add_all(repo_path)
            return 0, "", ""
        if subcommand == "commit":
            message = None
            index = 2
            while index < len(argv):
                token = argv[index]
                if token == "-m" and index + 1 < len(argv):
                    message = argv[index + 1]
                    index += 2
                    continue
                index += 1
            if not message:
                return 2, "", "git commit: only -m is supported in Cory sandbox\n"
            commit_id = cory_native.git_commit_all(
                repo_path,
                message,
                "Cory Sandbox",
                "cory@localhost",
            )
            return 0, f"[{commit_id[:7]}] {message}\n", ""
        if subcommand == "log":
            max_count = 20
            if len(argv) >= 4 and argv[2] in {"-n", "--max-count"}:
                max_count = int(argv[3])
            return 0, cory_native.git_log(repo_path, max_count=max_count), ""
        if subcommand == "checkout":
            if len(argv) < 3:
                return 2, "", "git checkout: expected TARGET\n"
            cory_native.git_checkout(repo_path, argv[2])
            return 0, f"Checked out {argv[2]}\n", ""
        if subcommand == "worktree":
            if len(argv) < 3:
                return 2, "", "git worktree: expected subcommand\n"
            worktree_command = argv[2]
            if worktree_command == "list":
                return 0, cory_native.git_worktree_list(repo_path), ""
            if worktree_command == "add":
                if len(argv) < 4:
                    return 2, "", "git worktree add: expected PATH [BRANCH]\n"
                worktree_path = str(_resolve_path(argv[3], cwd))
                worktree_name = pathlib.Path(worktree_path).name
                branch = argv[4] if len(argv) >= 5 else None
                cory_native.git_worktree_add(repo_path, worktree_name, worktree_path, branch)
                return 0, f"Added worktree {worktree_name} at {worktree_path}\n", ""
            return 2, "", f"git worktree: unsupported subcommand '{worktree_command}'\n"
    except Exception as exc:
        return 1, "", f"git: {exc}\n"

    return 2, "", f"git: unsupported subcommand '{subcommand}' in Cory sandbox\n"


def _handle_busybox(argv: list[str], cwd: str | None, env: dict[str, str]) -> tuple[int, str, str]:
    if _tool_exists("busybox", env):
        if len(argv) == 1:
            return _run_external([str(_tool_path("busybox", env)), "--list"], cwd, env)
        return _run_external([str(_tool_path("busybox", env)), *argv[1:]], cwd, env)
    if len(argv) == 1:
        applets = sorted(name for name in _COMMANDS if name != "busybox")
        return 0, " ".join(applets) + "\n", ""
    return _dispatch([argv[1], *argv[2:]], cwd, env)


def _handle_sh(argv: list[str], cwd: str | None, env: dict[str, str]) -> tuple[int, str, str]:
    if len(argv) >= 3 and argv[1] == "-c":
        return _dispatch(_as_argv(argv[2], True), cwd, env)
    return 2, "", "sh: only '-c' is supported in Cory sandbox\n"


_COMMANDS = {
    "busybox": _handle_busybox,
    "cat": _handle_cat,
    "cp": _handle_cp,
    "echo": _handle_echo,
    "env": _handle_env,
    "false": _handle_false,
    "find": _handle_find,
    "git": _handle_git,
    "head": _handle_head,
    "ls": _handle_ls,
    "mkdir": _handle_mkdir,
    "mv": _handle_mv,
    "node": _handle_node,
    "pwd": _handle_pwd,
    "python": _handle_python,
    "python3": _handle_python,
    "rg": _handle_rg,
    "rm": _handle_rm,
    "sh": _handle_sh,
    "tail": _handle_tail,
    "touch": _handle_touch,
    "true": _handle_true,
    "uname": _handle_uname,
    "wc": _handle_wc,
    "which": _handle_which,
}


def _dispatch(argv: list[str], cwd: str | None, env: dict[str, str]) -> tuple[int, str, str]:
    if not argv:
        return 0, "", ""
    handler = _COMMANDS.get(argv[0])
    if handler is None:
        if argv[0] in _busybox_applets(env):
            return _run_external([str(_tool_path("busybox", env)), *argv], cwd, env)
        return 127, "", f"{argv[0]}: command not found in Cory sandbox\n"
    _write_placeholder_command(argv[0], env)
    return handler(argv, cwd, env)


def run(command, *, cwd=None, env=None, shell=False, check=False, capture_output=False,
        text=None, input=None, stdout=None, stderr=None, **kwargs):
    del input, kwargs
    merged_env = dict(os.environ)
    if env:
        merged_env.update({str(key): str(value) for key, value in env.items()})

    argv = _as_argv(command, shell)
    returncode, out_text, err_text = _dispatch(argv, cwd, merged_env)
    wants_text = bool(text) or stdout == PIPE or stderr == PIPE or capture_output
    out = out_text if wants_text else out_text.encode("utf-8")
    err = err_text if wants_text else err_text.encode("utf-8")
    completed = CompletedProcess(argv, returncode, out, err)
    if check and returncode != 0:
        raise CalledProcessError(returncode, argv, output=out, stderr=err)
    return completed


class CoryPopen:
    def __init__(self, command, *, cwd=None, env=None, shell=False, text=False,
                 stdout=None, stderr=None, **kwargs):
        self.args = _as_argv(command, shell)
        result = run(
            self.args,
            cwd=cwd,
            env=env,
            shell=False,
            text=text or stdout == PIPE or stderr == PIPE,
            stdout=stdout,
            stderr=stderr,
            **kwargs,
        )
        self.returncode = result.returncode
        self._stdout = result.stdout
        self._stderr = result.stderr

    def communicate(self, input=None, timeout=None):
        del input, timeout
        return self._stdout, self._stderr

    def wait(self, timeout=None):
        del timeout
        return self.returncode

    def poll(self):
        return self.returncode


def check_call(command, **kwargs):
    return run(command, check=True, **kwargs).returncode


def check_output(command, **kwargs):
    return run(command, check=True, capture_output=True, text=True, **kwargs).stdout


def os_system(command: str) -> int:
    return run(command, shell=True).returncode


def install() -> None:
    global _INSTALLED
    if _INSTALLED:
        return
    os.system = os_system
    if _stdlib_subprocess is not None:
        _stdlib_subprocess.run = run
        _stdlib_subprocess.check_call = check_call
        _stdlib_subprocess.check_output = check_output
        _stdlib_subprocess.Popen = CoryPopen
    _INSTALLED = True
