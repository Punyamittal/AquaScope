"""
Global Hotkey Module
Listens for configurable keyboard shortcuts for bookmarking, pause/resume, and voice memos.
Uses the `keyboard` library for cross-platform hotkey detection.

Note: The `keyboard` library does not support modifier+letter hotkeys on macOS.
On darwin, start() is a no-op and hotkeys are unavailable (use the dashboard instead).
"""

import logging
import sys
import threading
from typing import Callable, Optional

# Import config first — triggers _setup_logging() so warnings below
# are properly formatted and captured by log handlers/file sinks.
from screenmind.config import settings

logger = logging.getLogger("screenmind.capture.hotkey")

# Conditional import: on macOS, `import keyboard` itself installs a low-level
# event tap that triggers an Input Monitoring permission prompt — skip entirely.
# On Linux, keyboard requires root — degrade gracefully with a warning.
keyboard = None
if sys.platform != "darwin":
    try:
        import keyboard
    except Exception as e:
        keyboard = None
        logger.warning("=" * 60)
        logger.warning("KEYBOARD NOT AVAILABLE: %s", e)
        logger.warning("Hotkey shortcuts (bookmark/pause/voice) won't work.")
        if sys.platform == "linux":
            logger.warning("On Linux, keyboard requires root or input group access.")
            logger.warning("Fix: sudo usermod -aG input $USER  (then reboot)")
        logger.warning("Restart ScreenMind after fixing to enable hotkeys.")
        logger.warning("=" * 60)


class HotkeyListener:
    """
    Listens for global hotkeys and triggers callbacks:
    - Ctrl+Shift+B: Bookmark current moment (instant capture)
    - Ctrl+Shift+P: Pause/resume capture toggle
    - Ctrl+Shift+V: Voice memo (hold to record, release to stop)
    """

    def __init__(
        self,
        bookmark_callback: Callable[[], None],
        pause_callback: Optional[Callable[[], None]] = None,
        voice_start_callback: Optional[Callable[[], None]] = None,
        voice_stop_callback: Optional[Callable[[], None]] = None,
    ):
        self._bookmark_callback = bookmark_callback
        self._pause_callback = pause_callback
        self._voice_start_callback = voice_start_callback
        self._voice_stop_callback = voice_stop_callback
        self._bookmark_hotkey = settings.bookmark_hotkey
        self._pause_hotkey = settings.pause_hotkey
        self._voice_hotkey = settings.voice_hotkey
        self._running = False
        self._voice_recording = False
        self._voice_cooldown = 0.0  # Timestamp — ignore starts until this time
        self._voice_hooks = []  # Store hook references for cleanup

    def start(self):
        """Register global hotkeys."""
        if self._running:
            return

        if keyboard is None:
            logger.warning("Hotkeys disabled — keyboard module not available. Use the dashboard.")
            return

        if sys.platform == "darwin":
            logger.info("Disabled on macOS (keyboard library lacks modifier+letter support). Use the dashboard.")
            return

        try:
            keyboard.add_hotkey(self._bookmark_hotkey, self._on_bookmark, suppress=False)
            logger.info(f"Registered bookmark hotkey: {self._bookmark_hotkey}")

            if self._pause_callback:
                keyboard.add_hotkey(self._pause_hotkey, self._on_pause, suppress=False)
                logger.info(f"Registered pause hotkey: {self._pause_hotkey}")

            if self._voice_start_callback and self._voice_stop_callback:
                hook1 = keyboard.on_press_key(
                    self._voice_hotkey.split("+")[-1],
                    self._on_voice_key_down,
                    suppress=False,
                )
                hook2 = keyboard.on_release_key(
                    self._voice_hotkey.split("+")[-1],
                    self._on_voice_key_up,
                    suppress=False,
                )
                self._voice_hooks = [hook1, hook2]
                logger.info(f"Registered voice memo hotkey: {self._voice_hotkey}")

            self._running = True
        except Exception as e:
            logger.error(f"Failed to register hotkey: {e}")
            logger.info("Try running as administrator for global hotkey support.")

    def _on_bookmark(self):
        """Called when the bookmark hotkey is pressed."""
        logger.info(f"Bookmark triggered! ({self._bookmark_hotkey})")
        try:
            self._bookmark_callback()
        except Exception as e:
            logger.error(f"Error in bookmark callback: {e}")

    def _on_pause(self):
        """Called when the pause hotkey is pressed."""
        logger.info(f"Pause toggle triggered! ({self._pause_hotkey})")
        try:
            if self._pause_callback:
                self._pause_callback()
        except Exception as e:
            logger.error(f"Error in pause callback: {e}")

    def _on_voice_key_down(self, event):
        """Called when voice hotkey key is pressed — check modifiers + cooldown."""
        import time
        if self._voice_recording:
            return
        # Cooldown: ignore starts for 2s after a stop (prevents key-repeat loop)
        if time.time() < self._voice_cooldown:
            return
        # Check that the required modifiers are held
        parts = self._voice_hotkey.split("+")
        modifiers = parts[:-1]  # e.g., ["ctrl", "shift"]
        all_held = all(keyboard.is_pressed(m) for m in modifiers)
        if all_held:
            self._voice_recording = True
            logger.info(f"Voice memo started ({self._voice_hotkey})")
            try:
                self._voice_start_callback()
            except Exception as e:
                logger.error(f"Error in voice start callback: {e}")
                self._voice_recording = False

    def _on_voice_key_up(self, event):
        """Called when voice hotkey key is released — stop recording."""
        import time
        if not self._voice_recording:
            return
        self._voice_recording = False
        self._voice_cooldown = time.time() + 2.0  # 2s cooldown before next start
        logger.info(f"Voice memo stopped ({self._voice_hotkey})")
        try:
            self._voice_stop_callback()
        except Exception as e:
            logger.error(f"Error in voice stop callback: {e}")

    def stop(self):
        """Unregister all global hotkeys."""
        if not self._running:
            return

        try:
            keyboard.remove_hotkey(self._bookmark_hotkey)
            if self._pause_callback:
                keyboard.remove_hotkey(self._pause_hotkey)
            for hook in self._voice_hooks:
                keyboard.unhook(hook)
            self._voice_hooks = []
        except Exception:
            pass

        self._running = False
        logger.info("Stopped listening.")

    @property
    def is_running(self) -> bool:
        return self._running
