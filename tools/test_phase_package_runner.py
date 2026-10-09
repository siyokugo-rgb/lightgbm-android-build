from __future__ import annotations

import hashlib
import json
import subprocess
import tempfile
import unittest
from pathlib import Path

import tools.phase_package_runner as runner


def git(repo: Path, *args: str) -> str:
    proc = subprocess.run(
        ["git", *args], cwd=repo, text=True, capture_output=True, check=True
    )
    return proc.stdout.strip()


class PhasePackageRunnerTest(unittest.TestCase):
    def make_repo(self) -> tuple[tempfile.TemporaryDirectory[str], Path, str]:
        td = tempfile.TemporaryDirectory()
        repo = Path(td.name) / "repo"
        repo.mkdir()
        git(repo, "init")
        git(repo, "config", "user.name", "test")
        git(repo, "config", "user.email", "test@example.invalid")
        (repo / "README.md").write_text("baseline\n", encoding="utf-8")
        git(repo, "add", "README.md")
        git(repo, "commit", "-m", "baseline")
        git(repo, "branch", "-M", "main")
        head = git(repo, "rev-parse", "HEAD")
        return td, repo, head

    def make_package(
        self,
        base: Path,
        head: str,
        *,
        patch_text: str | None = None,
        allowed_paths: list[str] | None = None,
        checks: list[dict[str, object]] | None = None,
    ) -> Path:
        package = base / "package"
        package.mkdir()
        if patch_text is None:
            patch_text = (
                "diff --git a/docs/test.txt b/docs/test.txt\n"
                "new file mode 100644\n"
                "index 0000000..df967b9\n"
                "--- /dev/null\n"
                "+++ b/docs/test.txt\n"
                "@@ -0,0 +1 @@\n"
                "+hello\n"
            )
        patch = package / "changes.patch"
        patch.write_text(patch_text, encoding="utf-8")
        manifest = {
            "format_version": 1,
            "phase_id": "phase-99-test",
            "expected_head": head,
            "patch_file": "changes.patch",
            "patch_sha256": hashlib.sha256(patch.read_bytes()).hexdigest(),
            "allowed_paths": allowed_paths or ["docs/test.txt"],
            "checks": checks or [{"kind": "git_diff_check"}],
        }
        (package / "manifest.json").write_text(
            json.dumps(manifest), encoding="utf-8"
        )
        return package

    def test_valid_package_applies_and_does_not_commit(self) -> None:
        td, repo, head = self.make_repo()
        self.addCleanup(td.cleanup)
        package = self.make_package(Path(td.name), head)
        rc = runner.apply_phase(package, repo)
        self.assertEqual(0, rc)
        self.assertEqual(head, git(repo, "rev-parse", "HEAD"))
        self.assertEqual("hello\n", (repo / "docs" / "test.txt").read_text(encoding="utf-8"))
        result = json.loads((package / "phase-result.json").read_text(encoding="utf-8"))
        self.assertEqual("PASS", result["status"])
        self.assertFalse(result["commit_performed"])
        self.assertFalse(result["push_performed"])

    def test_dry_run_leaves_repo_clean(self) -> None:
        td, repo, head = self.make_repo()
        self.addCleanup(td.cleanup)
        package = self.make_package(Path(td.name), head)
        rc = runner.apply_phase(package, repo, dry_run=True)
        self.assertEqual(0, rc)
        self.assertFalse((repo / "docs" / "test.txt").exists())
        self.assertEqual("", git(repo, "status", "--porcelain"))

    def test_rejects_patch_hash_mismatch(self) -> None:
        td, repo, head = self.make_repo()
        self.addCleanup(td.cleanup)
        package = self.make_package(Path(td.name), head)
        (package / "changes.patch").write_text("tampered\n", encoding="utf-8")
        rc = runner.apply_phase(package, repo)
        self.assertEqual(1, rc)
        self.assertEqual("", git(repo, "status", "--porcelain"))

    def test_rejects_path_traversal(self) -> None:
        td, repo, head = self.make_repo()
        self.addCleanup(td.cleanup)
        package = self.make_package(Path(td.name), head)
        manifest_path = package / "manifest.json"
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        manifest["allowed_paths"] = ["../outside.txt"]
        manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
        rc = runner.apply_phase(package, repo)
        self.assertEqual(1, rc)
        self.assertFalse((Path(td.name) / "outside.txt").exists())

    def test_rejects_wrong_head(self) -> None:
        td, repo, head = self.make_repo()
        self.addCleanup(td.cleanup)
        package = self.make_package(Path(td.name), "0" * 40)
        rc = runner.apply_phase(package, repo)
        self.assertEqual(1, rc)
        self.assertEqual(head, git(repo, "rev-parse", "HEAD"))
        self.assertEqual("", git(repo, "status", "--porcelain"))

    def test_remote_provider_is_not_required_or_trusted(self) -> None:
        td, repo, head = self.make_repo()
        self.addCleanup(td.cleanup)
        git(repo, "remote", "add", "origin", "https://github.com/test/repo.git")
        package = self.make_package(Path(td.name), head)
        rc = runner.apply_phase(package, repo, dry_run=True)
        self.assertEqual(0, rc)
        self.assertEqual("", git(repo, "status", "--porcelain"))

    def test_rejects_arbitrary_check_kind(self) -> None:
        td, repo, head = self.make_repo()
        self.addCleanup(td.cleanup)
        package = self.make_package(
            Path(td.name), head, checks=[{"kind": "shell", "command": "whoami"}]
        )
        rc = runner.apply_phase(package, repo)
        self.assertEqual(1, rc)
        self.assertEqual("", git(repo, "status", "--porcelain"))


    def test_rejects_executable_new_file_mode(self) -> None:
        td, repo, head = self.make_repo()
        self.addCleanup(td.cleanup)
        patch = (
            "diff --git a/docs/test.txt b/docs/test.txt\n"
            "new file mode 100755\n"
            "index 0000000..df967b9\n"
            "--- /dev/null\n"
            "+++ b/docs/test.txt\n"
            "@@ -0,0 +1 @@\n"
            "+hello\n"
        )
        package = self.make_package(Path(td.name), head, patch_text=patch)
        rc = runner.apply_phase(package, repo)
        self.assertEqual(1, rc)
        self.assertEqual("", git(repo, "status", "--porcelain"))

    def test_failed_check_rolls_back_when_safe(self) -> None:
        td, repo, head = self.make_repo()
        self.addCleanup(td.cleanup)
        package = self.make_package(
            Path(td.name),
            head,
            checks=[{"kind": "python_unittest", "modules": ["tools.test_missing_module"]}],
        )
        rc = runner.apply_phase(package, repo)
        self.assertEqual(1, rc)
        self.assertFalse((repo / "docs" / "test.txt").exists())
        self.assertEqual("", git(repo, "status", "--porcelain"))
        result = json.loads((package / "phase-result.json").read_text(encoding="utf-8"))
        self.assertTrue(result["rollback"]["ok"])


if __name__ == "__main__":
    unittest.main()
