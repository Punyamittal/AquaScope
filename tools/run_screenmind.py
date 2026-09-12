#!/usr/bin/env python3
"""
ScreenMind Launcher for AquaScope.
Starts the ScreenMind local server (:7777) and opens the web dashboard.
"""
import os
import sys

# Ensure AquaScope/screenmind is in sys.path
BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SCREENMIND_DIR = os.path.join(BASE_DIR, "screenmind")

if SCREENMIND_DIR not in sys.path:
    sys.path.insert(0, SCREENMIND_DIR)

if __name__ == "__main__":
    os.chdir(SCREENMIND_DIR)
    from screenmind.main import main
    main()
