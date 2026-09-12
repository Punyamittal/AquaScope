"""
Windows Platform Adapter
Uses Win32 APIs (ctypes) for window detection and UI Automation for a11y.
"""

import logging
import os
import sys
import time
from typing import Optional, Tuple

from screenmind.platform_support.base import PlatformAdapter

logger = logging.getLogger("screenmind.platform_support.windows")


class WindowsAdapter(PlatformAdapter):
    """Windows implementation using ctypes + UI Automation."""

    def __init__(self):
        self._a11y_initialized = False
        self._uia = None
        self._a11y_available = True

    @property
    def platform_name(self) -> str:
        return "Windows"

    def get_foreground_window_handle(self) -> Optional[int]:
        """Get the foreground window handle using Win32 API."""
        try:
            import ctypes
            hwnd = ctypes.windll.user32.GetForegroundWindow()
            return hwnd if hwnd else None
        except Exception:
            return None

    def get_active_window_title(self) -> Optional[str]:
        """Get window title using Win32 GetWindowTextW."""
        try:
            import ctypes

            hwnd = ctypes.windll.user32.GetForegroundWindow()
            length = ctypes.windll.user32.GetWindowTextLengthW(hwnd)
            if length == 0:
                return None
            buf = ctypes.create_unicode_buffer(length + 1)
            ctypes.windll.user32.GetWindowTextW(hwnd, buf, length + 1)
            return buf.value if buf.value else None
        except Exception:
            return None

    def get_active_app_name(self) -> Optional[str]:
        """Get process name via Win32 APIs."""
        try:
            import ctypes
            from ctypes import wintypes

            hwnd = ctypes.windll.user32.GetForegroundWindow()

            # Get process ID from window handle
            pid = wintypes.DWORD()
            ctypes.windll.user32.GetWindowThreadProcessId(hwnd, ctypes.byref(pid))

            # Open process and get executable name
            PROCESS_QUERY_INFORMATION = 0x0400
            PROCESS_VM_READ = 0x0010
            handle = ctypes.windll.kernel32.OpenProcess(
                PROCESS_QUERY_INFORMATION | PROCESS_VM_READ, False, pid.value
            )

            if handle:
                try:
                    buf = ctypes.create_unicode_buffer(260)
                    size = wintypes.DWORD(260)
                    ctypes.windll.kernel32.QueryFullProcessImageNameW(
                        handle, 0, buf, ctypes.byref(size)
                    )
                    if buf.value:
                        name = os.path.basename(buf.value)
                        return os.path.splitext(name)[0]
                finally:
                    ctypes.windll.kernel32.CloseHandle(handle)

            return None
        except Exception:
            return None

    # ── Accessibility ────────────────────────────────────────────────

    def _ensure_a11y_init(self):
        """Lazy-init the UI Automation client."""
        if self._a11y_initialized:
            return
        self._a11y_initialized = True

        try:
            import uiautomation as auto
            self._uia = auto
            logger.debug("Windows UI Automation initialized")
        except ImportError:
            try:
                self._uia = None
                logger.warning("uiautomation not available, trying ctypes fallback")
            except Exception:
                self._a11y_available = False
                logger.warning("Accessibility API not available on this system")

    def is_a11y_available(self) -> bool:
        return self._a11y_available

    def extract_a11y_text(self, hwnd: Optional[int] = None) -> Tuple[Optional[str], str]:
        """Extract text using Windows UI Automation or ctypes fallback."""
        if not self._a11y_available:
            return None, "none"

        self._ensure_a11y_init()

        try:
            start = time.time()

            if self._uia:
                text = self._extract_uiautomation(hwnd)
            else:
                text = self._extract_ctypes(hwnd)

            elapsed = time.time() - start

            if text and len(text.strip()) > 20:
                lines = [l for l in text.strip().split('\n') if l.strip()]
                logger.debug(f"Extracted {len(lines)} text elements in {elapsed:.2f}s")
                return text.strip(), "a11y"
            else:
                return None, "none"

        except Exception as e:
            logger.error(f"Extraction failed: {e}")
            return None, "none"

    def _extract_uiautomation(self, hwnd: Optional[int] = None) -> Optional[str]:
        """Extract text using the uiautomation library."""
        auto = self._uia

        try:
            if hwnd:
                window = auto.ControlFromHandle(hwnd)
            else:
                window = auto.GetForegroundControl()

            if not window:
                return None

            texts = []
            self._walk_tree(window, texts, depth=0, max_depth=8)

            return '\n'.join(texts) if texts else None

        except Exception:
            return None

    def _walk_tree(self, control, texts: list, depth: int, max_depth: int = 8):
        """Recursively walk the UI Automation tree and extract text."""
        if depth > max_depth:
            return

        try:
            name = control.Name
            if name and name.strip() and len(name.strip()) > 1:
                text = name.strip()
                if text not in texts[-5:] if texts else True:
                    texts.append(text)

            try:
                value = control.GetValuePattern().Value
                if value and value.strip() and value.strip() != name:
                    texts.append(value.strip())
            except Exception:
                pass

            children = control.GetChildren()
            if children:
                for child in children:
                    self._walk_tree(child, texts, depth + 1, max_depth)
                    if len(texts) > 500:
                        return

        except Exception:
            pass

    def _extract_ctypes(self, hwnd: Optional[int] = None) -> Optional[str]:
        """Fallback: Extract text using raw Win32 APIs via ctypes."""
        try:
            import ctypes
            from ctypes import wintypes
            user32 = ctypes.windll.user32

            if hwnd is None:
                hwnd = user32.GetForegroundWindow()

            if not hwnd:
                return None

            texts = []

            WNDENUMPROC = ctypes.WINFUNCTYPE(
                wintypes.BOOL, wintypes.HWND, wintypes.LPARAM
            )

            def enum_callback(child_hwnd, _lparam):
                length = user32.GetWindowTextLengthW(child_hwnd)
                if length > 0:
                    buf = ctypes.create_unicode_buffer(length + 1)
                    user32.GetWindowTextW(child_hwnd, buf, length + 1)
                    text = buf.value.strip()
                    if text and len(text) > 1:
                        texts.append(text)
                return len(texts) < 300

            user32.EnumChildWindows(hwnd, WNDENUMPROC(enum_callback), 0)

            return '\n'.join(texts) if texts else None

        except Exception:
            return None
