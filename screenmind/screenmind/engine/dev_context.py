"""
Developer Context Engine (The CodeRadar Layer)
Detects active git repositories and enriches coding activities
with branch, commit, and file change information.
"""

import logging
import os
from pathlib import Path
from typing import Optional, List

from screenmind.config import settings
from screenmind.storage.models import DevContext

logger = logging.getLogger("screenmind.engine.dev_context")


class DevContextDetector:
    """
    Scans workspace directories for git repos and extracts
    context from the most recently active one.
    """

    # Known IDE/terminal process names that indicate coding
    CODING_APPS = {
        "code", "code - insiders",  # VS Code
        "devenv",                    # Visual Studio
        "idea64", "pycharm64", "webstorm64", "goland64",  # JetBrains
        "sublime_text",              # Sublime
        "atom",                      # Atom
        "notepad++",                 # Notepad++
        "windowsterminal", "cmd", "powershell", "pwsh",  # Terminals
        "wt",                        # Windows Terminal
        "mintty", "git-bash",        # Git Bash
        "alacritty", "wezterm",      # Modern terminals
    }

    # Keywords in window titles that suggest coding
    CODING_TITLE_KEYWORDS = [
        ".py", ".js", ".ts", ".go", ".rs", ".java", ".cpp", ".c",
        ".html", ".css", ".jsx", ".tsx", ".vue", ".rb", ".php",
        "Visual Studio Code", "VS Code", "PyCharm", "IntelliJ",
        "terminal", "Terminal", "PowerShell", "cmd.exe",
        "git ", "npm ", "pip ", "python ", "node ",
    ]

    def __init__(self):
        self._workspace_dirs = settings.workspace_dirs_list
        self._repo_cache: dict[str, "git.Repo"] = {}

    def is_coding_activity(
        self,
        category: Optional[str] = None,
        app_name: Optional[str] = None,
        window_title: Optional[str] = None,
    ) -> bool:
        """
        Determine if the current activity is coding-related.
        Uses multiple signals: Gemma's category, OS app name, window title.
        """
        # Signal 1: Gemma classified it as coding or terminal
        if category and category.lower() in ("coding", "terminal"):
            return True

        # Signal 2: Known coding app process
        if app_name and app_name.lower().replace(".exe", "") in self.CODING_APPS:
            return True

        # Signal 3: Window title contains coding-related keywords
        if window_title:
            title_lower = window_title.lower()
            return any(kw.lower() in title_lower for kw in self.CODING_TITLE_KEYWORDS)

        return False

    def get_context(
        self,
        window_title: Optional[str] = None,
        visible_text: Optional[List[str]] = None,
    ) -> Optional[DevContext]:
        """
        Extract git context from the most relevant active repository.

        Args:
            window_title: Current window title (may contain file paths).
            visible_text: Text snippets visible on screen (from Gemma analysis).

        Returns:
            DevContext with repo info, or None if no git repo found.
        """
        try:
            import git

            repo = self._find_active_repo(window_title, visible_text)
            if repo is None:
                return None

            return self._extract_context(repo)

        except ImportError:
            logger.debug("gitpython not installed, skipping git context")
            return None
        except Exception as e:
            logger.error(f"Error getting context: {e}")
            return None

    def _find_active_repo(
        self,
        window_title: Optional[str] = None,
        visible_text: Optional[List[str]] = None,
    ) -> Optional["git.Repo"]:
        """
        Find the most likely active git repository based on available signals.
        Strategy:
        1. Extract file paths from window title / visible text
        2. Walk up from those paths to find .git directories
        3. Fall back to most recently modified repo in workspace dirs
        """
        import git

        # Strategy 1: Extract paths from window title
        candidate_paths = self._extract_paths_from_text(window_title or "")
        if visible_text:
            for text in visible_text:
                candidate_paths.extend(self._extract_paths_from_text(text))

        # Try to find repo from candidate paths
        for path_str in candidate_paths:
            path = Path(path_str).resolve()
            try:
                # Walk up to find .git
                for parent in [path] + list(path.parents):
                    if (parent / ".git").exists():
                        repo = git.Repo(str(parent))
                        if not repo.bare:
                            return repo
            except (git.InvalidGitRepositoryError, OSError):
                continue

        # Strategy 2: Most recently modified repo in workspace dirs
        return self._find_most_recent_repo()

    def _find_most_recent_repo(self) -> Optional["git.Repo"]:
        """Find the git repo with the most recent activity in workspace dirs.
        Scans up to 4 levels deep to handle nested project structures."""
        import git

        most_recent = None
        most_recent_time = 0
        max_depth = 4

        for ws_dir in self._workspace_dirs:
            ws_path = Path(ws_dir).resolve()
            if not ws_path.exists():
                continue

            for root, dirs, _files in os.walk(str(ws_path)):
                # Enforce depth limit
                depth = len(Path(root).relative_to(ws_path).parts)
                if depth >= max_depth:
                    dirs.clear()  # Stop descending
                    continue

                if ".git" in dirs:
                    git_path = Path(root)
                    try:
                        mtime = (git_path / ".git").stat().st_mtime
                        if mtime > most_recent_time:
                            repo = git.Repo(str(git_path))
                            if not repo.bare:
                                most_recent = repo
                                most_recent_time = mtime
                    except (git.InvalidGitRepositoryError, OSError):
                        pass
                    dirs.remove(".git")  # Don't descend into .git itself

        return most_recent

    def _extract_context(self, repo: "git.Repo") -> DevContext:
        """Extract structured git context from a repository."""
        ctx = DevContext()

        # Repo name (folder name)
        ctx.repo_name = Path(repo.working_dir).name

        # Current branch
        try:
            ctx.branch = str(repo.active_branch)
        except TypeError:
            ctx.branch = "detached HEAD"

        # Last commit
        try:
            last_commit = repo.head.commit
            ctx.last_commit = last_commit.message.strip().split("\n")[0][:100]
        except (ValueError, TypeError):
            ctx.last_commit = ""

        # Changed files (staged + unstaged)
        try:
            changed = set()
            # Unstaged changes
            for diff in repo.index.diff(None):
                changed.add(diff.a_path)
            # Staged changes
            try:
                for diff in repo.index.diff("HEAD"):
                    changed.add(diff.a_path)
            except Exception:
                pass
            # Untracked files
            changed.update(repo.untracked_files[:10])

            ctx.changed_files = sorted(list(changed))[:15]  # Cap at 15 files
        except Exception:
            ctx.changed_files = []

        # Insertions/deletions (from diff stat)
        try:
            diff_stat = repo.git.diff("--shortstat")
            if diff_stat:
                import re
                ins_match = re.search(r"(\d+) insertion", diff_stat)
                del_match = re.search(r"(\d+) deletion", diff_stat)
                ctx.insertions = int(ins_match.group(1)) if ins_match else 0
                ctx.deletions = int(del_match.group(1)) if del_match else 0
        except Exception:
            pass

        return ctx

    def _extract_paths_from_text(self, text: str) -> List[str]:
        """Extract potential file paths from text (window titles, visible text)."""
        import re

        paths = []

        # Windows-style paths: C:\Users\...\file.py
        win_paths = re.findall(r"[A-Za-z]:\\[^\s:*?\"<>|]+", text)
        paths.extend(win_paths)

        # Unix-style paths: /home/user/.../file.py or ~/projects/...
        unix_paths = re.findall(r"(?:~|/)[^\s:*?\"<>|]+", text)
        paths.extend(unix_paths)

        # Relative paths with extensions: src/main.py, lib/utils.js
        rel_paths = re.findall(r"\b[\w./\\-]+\.\w{1,4}\b", text)
        paths.extend(p for p in rel_paths if "/" in p or "\\" in p)

        return paths
