from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path, PurePosixPath
from typing import Any

FORMAT_VERSION = 1
MAX_PATCH_BYTES = 4 * 1024 * 1024
MAX_CHANGED_PATHS = 100
PHASE_RE = re.compile(r"^phase-[0-9]{2}(?:-[a-z0-9][a-z0-9-]*)?$")
HEX40_RE = re.compile(r"^[0-9a-f]{40}$")
SAFE_PATCH_PATH_RE = re.compile(r"^[A-Za-z0-9._/-]+$")
PY_TEST_MODULE_RE = re.compile(r"^tools\.test_[A-Za-z0-9_]+$")
GRADLE_TASK_RE = re.compile(r"^(?::?[A-Za-z0-9_.-]+)*(?::[A-Za-z0-9_.-]+)+$")
GRADLE_TEST_RE = re.compile(r"^com\.keiba\.ai\.[A-Za-z0-9_.$]+$")
ALLOWED_CHECK_KINDS = {
    "git_diff_check",
    "python_unittest",
    "python_contract_discovery",
    "gradle",
}


class PhasePackageError(RuntimeError):
    pass


@dataclass(frozen=True)
class Manifest:
    phase_id: str
    expected_head: str
    patch_file: str
    patch_sha256: str
    allowed_paths: tuple[str, ...]
    checks: tuple[dict[str, Any], ...]


def _run(
    argv: list[str],
    *,
    cwd: Path,
    check: bool = True,
    capture: bool = True,
) -> subprocess.CompletedProcess[str]:
    proc = subprocess.run(
        argv,
        cwd=str(cwd),
        text=True,
        capture_output=capture,
        check=False,
    )
    if check and proc.returncode != 0:
        detail = (proc.stdout or "") + (proc.stderr or "")
        raise PhasePackageError(
            f"command failed ({proc.returncode}): {' '.join(argv)}\n{detail.strip()}"
        )
    return proc


def _git(repo: Path, *args: str, check: bool = True) -> str:
    proc = _run(["git", *args], cwd=repo, check=check)
    return (proc.stdout or "").strip()


def _sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def _safe_repo_path(value: str) -> str:
    if not isinstance(value, str) or not value:
        raise PhasePackageError("allowed_paths entries must be non-empty strings")
    if "\\" in value or "\x00" in value or ":" in value:
        raise PhasePackageError(f"unsafe repository path: {value!r}")
    if not SAFE_PATCH_PATH_RE.fullmatch(value):
        raise PhasePackageError(f"unsupported repository path characters: {value!r}")
    p = PurePosixPath(value)
    if p.is_absolute() or any(part in {"", ".", "..", ".git"} for part in p.parts):
        raise PhasePackageError(f"unsafe repository path: {value!r}")
    return value


def load_manifest(package_dir: Path) -> Manifest:
    manifest_path = package_dir / "manifest.json"
    if not manifest_path.is_file() or manifest_path.is_symlink():
        raise PhasePackageError("manifest.json is missing or is a symlink")
    try:
        raw = json.loads(manifest_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise PhasePackageError(f"cannot read manifest.json: {exc}") from exc

    required = {
        "format_version",
        "phase_id",
        "expected_head",
        "patch_file",
        "patch_sha256",
        "allowed_paths",
        "checks",
    }
    if set(raw) != required:
        missing = sorted(required - set(raw))
        extra = sorted(set(raw) - required)
        raise PhasePackageError(f"manifest keys mismatch; missing={missing}, extra={extra}")
    if raw["format_version"] != FORMAT_VERSION:
        raise PhasePackageError("unsupported format_version")

    phase_id = raw["phase_id"]
    if not isinstance(phase_id, str) or not PHASE_RE.fullmatch(phase_id):
        raise PhasePackageError("invalid phase_id")

    expected_head = raw["expected_head"]
    if not isinstance(expected_head, str) or not HEX40_RE.fullmatch(expected_head):
        raise PhasePackageError("expected_head must be a lowercase 40-hex SHA-1")

    patch_file = raw["patch_file"]
    if patch_file != "changes.patch":
        raise PhasePackageError("patch_file must be exactly changes.patch")

    patch_sha256 = raw["patch_sha256"]
    if not isinstance(patch_sha256, str) or not re.fullmatch(r"[0-9a-f]{64}", patch_sha256):
        raise PhasePackageError("patch_sha256 must be lowercase 64-hex")

    allowed_raw = raw["allowed_paths"]
    if not isinstance(allowed_raw, list) or not allowed_raw:
        raise PhasePackageError("allowed_paths must be a non-empty list")
    allowed = tuple(_safe_repo_path(v) for v in allowed_raw)
    if len(set(allowed)) != len(allowed):
        raise PhasePackageError("allowed_paths contains duplicates")
    if len(allowed) > MAX_CHANGED_PATHS:
        raise PhasePackageError("too many allowed_paths")

    checks_raw = raw["checks"]
    if not isinstance(checks_raw, list) or not checks_raw:
        raise PhasePackageError("checks must be a non-empty list")
    checks: list[dict[str, Any]] = []
    for item in checks_raw:
        if not isinstance(item, dict) or "kind" not in item:
            raise PhasePackageError("every check must be an object with kind")
        kind = item["kind"]
        if kind not in ALLOWED_CHECK_KINDS:
            raise PhasePackageError(f"unsupported check kind: {kind!r}")
        _validate_check(item)
        checks.append(item)

    return Manifest(
        phase_id=phase_id,
        expected_head=expected_head,
        patch_file=patch_file,
        patch_sha256=patch_sha256,
        allowed_paths=allowed,
        checks=tuple(checks),
    )


def _validate_check(item: dict[str, Any]) -> None:
    kind = item["kind"]
    if kind == "git_diff_check":
        if set(item) != {"kind"}:
            raise PhasePackageError("git_diff_check accepts no extra fields")
        return
    if kind == "python_contract_discovery":
        if set(item) != {"kind"}:
            raise PhasePackageError("python_contract_discovery accepts no extra fields")
        return
    if kind == "python_unittest":
        if set(item) != {"kind", "modules"}:
            raise PhasePackageError("python_unittest requires only modules")
        modules = item["modules"]
        if not isinstance(modules, list) or not modules:
            raise PhasePackageError("python_unittest.modules must be non-empty")
        for module in modules:
            if not isinstance(module, str) or not PY_TEST_MODULE_RE.fullmatch(module):
                raise PhasePackageError(f"unsafe unittest module: {module!r}")
        return
    if kind == "gradle":
        if not set(item).issubset({"kind", "tasks", "tests"}) or "tasks" not in item:
            raise PhasePackageError("gradle check requires tasks and optional tests")
        tasks = item["tasks"]
        tests = item.get("tests", [])
        if not isinstance(tasks, list) or not tasks:
            raise PhasePackageError("gradle.tasks must be non-empty")
        if not isinstance(tests, list):
            raise PhasePackageError("gradle.tests must be a list")
        for task in tasks:
            if not isinstance(task, str) or not GRADLE_TASK_RE.fullmatch(task):
                raise PhasePackageError(f"unsafe gradle task: {task!r}")
        for test in tests:
            if not isinstance(test, str) or not GRADLE_TEST_RE.fullmatch(test):
                raise PhasePackageError(f"unsafe gradle test filter: {test!r}")
        return
    raise AssertionError(kind)


def validate_patch(package_dir: Path, manifest: Manifest) -> tuple[Path, tuple[str, ...]]:
    patch = package_dir / manifest.patch_file
    if not patch.is_file() or patch.is_symlink():
        raise PhasePackageError("changes.patch is missing or is a symlink")
    size = patch.stat().st_size
    if size <= 0 or size > MAX_PATCH_BYTES:
        raise PhasePackageError(f"changes.patch size out of range: {size}")
    actual_hash = _sha256(patch)
    if actual_hash != manifest.patch_sha256:
        raise PhasePackageError("changes.patch SHA-256 mismatch")

    try:
        text = patch.read_text(encoding="utf-8")
    except UnicodeDecodeError as exc:
        raise PhasePackageError("changes.patch must be UTF-8 text") from exc
    if "\x00" in text:
        raise PhasePackageError("NUL byte is forbidden in patch")
    changed: list[str] = []
    lines = text.splitlines()
    for line in lines:
        if line == "GIT binary patch" or line.startswith("Binary files "):
            raise PhasePackageError(f"forbidden binary patch construct: {line!r}")
        if line.startswith("new file mode ") and line != "new file mode 100644":
            raise PhasePackageError(f"new files must use mode 100644: {line!r}")
        if line.startswith("old mode ") or line.startswith("new mode "):
            raise PhasePackageError(f"mode changes are forbidden: {line!r}")
        if line.startswith(("rename from ", "rename to ", "copy from ", "copy to ")):
            raise PhasePackageError(f"rename/copy patch constructs are forbidden: {line!r}")
        if not line.startswith("diff --git "):
            continue
        match = re.fullmatch(
            r"diff --git a/([A-Za-z0-9._/-]+) b/([A-Za-z0-9._/-]+)", line
        )
        if not match:
            raise PhasePackageError(f"unsupported diff header: {line!r}")
        left = _safe_repo_path(match.group(1))
        right = _safe_repo_path(match.group(2))
        if left != right:
            raise PhasePackageError("renames are forbidden")
        changed.append(right)

    allowed_set = set(manifest.allowed_paths)
    for line in lines:
        if line.startswith("--- "):
            value = line[4:]
            if value != "/dev/null":
                if not value.startswith("a/") or _safe_repo_path(value[2:]) not in allowed_set:
                    raise PhasePackageError(f"unsafe --- patch path: {value!r}")
        elif line.startswith("+++ "):
            value = line[4:]
            if value != "/dev/null":
                if not value.startswith("b/") or _safe_repo_path(value[2:]) not in allowed_set:
                    raise PhasePackageError(f"unsafe +++ patch path: {value!r}")

    if not changed:
        raise PhasePackageError("patch contains no file diffs")
    if len(changed) != len(set(changed)):
        raise PhasePackageError("patch contains duplicate file diffs")
    if set(changed) != set(manifest.allowed_paths):
        raise PhasePackageError(
            f"patch paths do not match allowed_paths: patch={sorted(changed)} "
            f"manifest={sorted(manifest.allowed_paths)}"
        )
    return patch, tuple(changed)


def verify_repo(repo: Path, manifest: Manifest) -> None:
    repo = repo.resolve()
    if not repo.is_dir():
        raise PhasePackageError(f"repository does not exist: {repo}")
    top = Path(_git(repo, "rev-parse", "--show-toplevel")).resolve()
    if top != repo:
        raise PhasePackageError(f"repo path must be the Git top-level: {top}")
    branch = _git(repo, "branch", "--show-current")
    if branch != "main":
        raise PhasePackageError(f"expected branch main, found {branch!r}")
    head = _git(repo, "rev-parse", "HEAD")
    if head != manifest.expected_head:
        raise PhasePackageError(
            f"HEAD mismatch: expected {manifest.expected_head}, found {head}"
        )
    status = _git(repo, "status", "--porcelain=v1", "--untracked-files=all")
    if status:
        raise PhasePackageError("working tree must be clean before applying a phase")
    git_dir = Path(_git(repo, "rev-parse", "--git-dir"))
    if not git_dir.is_absolute():
        git_dir = repo / git_dir
    for marker in ("MERGE_HEAD", "CHERRY_PICK_HEAD", "REVERT_HEAD", "REBASE_HEAD"):
        if (git_dir / marker).exists():
            raise PhasePackageError(f"Git operation in progress: {marker}")
    for rebase_dir in ("rebase-apply", "rebase-merge"):
        if (git_dir / rebase_dir).exists():
            raise PhasePackageError(f"Git rebase in progress: {rebase_dir}")


def _status_paths(repo: Path) -> set[str]:
    proc = _run(
        ["git", "status", "--porcelain=v1", "-z", "--untracked-files=all"],
        cwd=repo,
    )
    raw = proc.stdout or ""
    paths: set[str] = set()
    entries = raw.split("\x00")
    for entry in entries:
        if not entry:
            continue
        if len(entry) < 4:
            raise PhasePackageError(f"unexpected git status entry: {entry!r}")
        path = entry[3:]
        if " -> " in path:
            raise PhasePackageError("renames in working tree are forbidden")
        paths.add(path.replace("\\", "/"))
    return paths


def _run_check(repo: Path, check: dict[str, Any]) -> None:
    kind = check["kind"]
    if kind == "git_diff_check":
        _run(["git", "-c", "core.whitespace=cr-at-eol", "diff", "--check"], cwd=repo)
        return
    if kind == "python_unittest":
        _run([sys.executable, "-m", "unittest", *check["modules"]], cwd=repo)
        return
    if kind == "python_contract_discovery":
        _run(
            [
                sys.executable,
                "-m",
                "unittest",
                "discover",
                "-s",
                "tools",
                "-p",
                "test_*_contract.py",
            ],
            cwd=repo,
        )
        return
    if kind == "gradle":
        tasks = check["tasks"]
        tests = check.get("tests", [])
        if os.name == "nt":
            gradle = repo / "gradlew.bat"
        else:
            gradle = repo / "gradlew"
        if not gradle.exists():
            raise PhasePackageError(f"Gradle wrapper not found: {gradle}")
        argv = [str(gradle), "--no-daemon", *tasks]
        for test in tests:
            argv.extend(["--tests", test])
        _run(argv, cwd=repo, capture=False)
        return
    raise PhasePackageError(f"unsupported check kind: {kind}")


def _write_report(package_dir: Path, report: dict[str, Any]) -> None:
    path = package_dir / "phase-result.json"
    tmp = package_dir / "phase-result.json.tmp"
    if path.is_symlink() or tmp.is_symlink():
        raise PhasePackageError("report path must not be a symlink")
    if tmp.exists():
        tmp.unlink()
    payload = json.dumps(report, ensure_ascii=False, indent=2) + "\n"
    tmp.write_text(payload, encoding="utf-8")
    tmp.replace(path)


def _rollback_if_safe(repo: Path, patch: Path, allowed: set[str]) -> tuple[bool, str]:
    try:
        current = _status_paths(repo)
        if current != allowed:
            return False, f"rollback refused: working-tree paths changed unexpectedly: {sorted(current)}"
        check = _run(
            ["git", "-c", "core.whitespace=cr-at-eol", "apply", "--reverse", "--check", "--whitespace=error-all", str(patch)],
            cwd=repo,
            check=False,
        )
        if check.returncode != 0:
            return False, "rollback refused: reverse patch check failed"
        _run(
            ["git", "-c", "core.whitespace=cr-at-eol", "apply", "--reverse", "--whitespace=error-all", str(patch)],
            cwd=repo,
        )
        remaining = _status_paths(repo)
        if remaining:
            return False, f"rollback incomplete; remaining changes: {sorted(remaining)}"
        return True, "rollback completed"
    except Exception as exc:  # defensive: report, never hide rollback failure
        return False, f"rollback exception: {exc}"


def apply_phase(package_dir: Path, repo: Path, *, dry_run: bool = False) -> int:
    package_dir = package_dir.resolve()
    repo = repo.resolve()
    report: dict[str, Any] = {
        "status": "STARTED",
        "package_dir": str(package_dir),
        "repo": str(repo),
        "dry_run": dry_run,
        "rollback": None,
    }
    applied = False
    manifest: Manifest | None = None
    patch: Path | None = None
    allowed: set[str] = set()
    try:
        manifest = load_manifest(package_dir)
        report["phase_id"] = manifest.phase_id
        report["expected_head"] = manifest.expected_head
        patch, changed = validate_patch(package_dir, manifest)
        allowed = set(changed)
        verify_repo(repo, manifest)
        _run(
            ["git", "-c", "core.whitespace=cr-at-eol", "apply", "--check", "--whitespace=error-all", str(patch)],
            cwd=repo,
        )
        if dry_run:
            report["status"] = "DRY_RUN_PASS"
            _write_report(package_dir, report)
            return 0

        _run(["git", "-c", "core.whitespace=cr-at-eol", "apply", "--whitespace=error-all", str(patch)], cwd=repo)
        applied = True
        current = _status_paths(repo)
        if current != allowed:
            raise PhasePackageError(
                f"post-apply paths mismatch: expected={sorted(allowed)} actual={sorted(current)}"
            )
        for check in manifest.checks:
            _run_check(repo, check)
        current = _status_paths(repo)
        if current != allowed:
            raise PhasePackageError(
                f"checks changed unexpected tracked paths: expected={sorted(allowed)} actual={sorted(current)}"
            )
        report["status"] = "PASS"
        report["changed_paths"] = sorted(allowed)
        report["commit_performed"] = False
        report["push_performed"] = False
        _write_report(package_dir, report)
        return 0
    except Exception as exc:
        report["status"] = "FAILED"
        report["error"] = str(exc)
        if applied and patch is not None:
            ok, message = _rollback_if_safe(repo, patch, allowed)
            report["rollback"] = {"ok": ok, "message": message}
        try:
            _write_report(package_dir, report)
        except Exception:
            pass
        print(f"PHASE PACKAGE FAILED: {exc}", file=sys.stderr)
        if report.get("rollback"):
            print(f"ROLLBACK: {report['rollback']}", file=sys.stderr)
        return 1


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Apply one audited local phase package safely.")
    parser.add_argument("--package", required=True, type=Path)
    parser.add_argument("--repo", required=True, type=Path)
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args(argv)
    return apply_phase(args.package, args.repo, dry_run=args.dry_run)


if __name__ == "__main__":
    raise SystemExit(main())
