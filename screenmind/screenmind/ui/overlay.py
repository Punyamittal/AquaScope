"""
System-wide overlay notification for ScreenMind.
Shows a modern floating notification on top of all windows.
Uses tkinter via subprocess to avoid threading issues.
"""

import subprocess
import sys


def show_overlay_notification(title: str, message: str, duration: float = 4.0, color: str = "#8b5cf6"):
    """
    Show a system-wide floating notification at top-right of screen.
    Modern glass-like dark theme with smooth slide + fade animation.

    Args:
        title: Bold heading text
        message: Subtitle text
        duration: How long to show (seconds)
        color: Accent color for border and title
    """
    safe_title = title.replace('"', '\\"').replace("'", "\\'").replace('\n', ' ')
    safe_message = message.replace('"', '\\"').replace("'", "\\'").replace('\n', ' ')

    script = f'''
import tkinter as tk
import tkinter.font as tkfont

root = tk.Tk()
root.overrideredirect(True)
root.attributes("-topmost", True)
root.attributes("-alpha", 0.0)

# ── Layout ────────────────────────────────────────────
width = 360
height = 82
screen_w = root.winfo_screenwidth()
# Slide in from right edge
final_x = screen_w - width - 20
start_x = screen_w + 10
y = 20
root.geometry(f"{{width}}x{{height}}+{{start_x}}+{{y}}")

# ── Colors ────────────────────────────────────────────
accent = "{color}"
bg = "#11111b"
bg_card = "#16162a"
fg_title = "#e2e2f0"
fg_msg = "#8888a8"
border_color = "#252540"

# ── Root with transparent key ─────────────────────────
try:
    root.attributes("-transparentcolor", "#010101")
    root.configure(bg="#010101")
except Exception:
    root.configure(bg=bg)

# ── Card frame ────────────────────────────────────────
card = tk.Frame(root, bg=bg_card, highlightbackground=border_color,
                highlightthickness=1)
card.place(x=2, y=2, width=width - 4, height=height - 4)

# ── Accent stripe (left) ─────────────────────────────
stripe = tk.Frame(card, bg=accent, width=3)
stripe.pack(side="left", fill="y")

# ── Content area ─────────────────────────────────────
body = tk.Frame(card, bg=bg_card)
body.pack(side="left", fill="both", expand=True, padx=(14, 10), pady=(12, 10))

# Title with accent color
t = tk.Label(body, text="{safe_title}", font=("Segoe UI Semibold", 10),
             bg=bg_card, fg=accent, anchor="w")
t.pack(fill="x")

# Message
m = tk.Label(body, text="{safe_message}", font=("Segoe UI", 8),
             bg=bg_card, fg=fg_msg, anchor="w", wraplength=280, justify="left")
m.pack(fill="x", pady=(3, 0))

# ── Subtle progress bar (bottom) ─────────────────────
bar_bg = tk.Frame(card, bg=border_color, height=2)
bar_bg.pack(side="bottom", fill="x")
bar_fill = tk.Frame(bar_bg, bg=accent, height=2)
bar_fill.place(relx=0, rely=0, relwidth=1.0, relheight=1.0)

# ── Close "x" ────────────────────────────────────────
close_lbl = tk.Label(card, text="\\u00d7", font=("Segoe UI", 11), bg=bg_card,
                      fg="#444460", cursor="hand2", padx=6)
close_lbl.pack(side="right", anchor="ne", pady=(6, 0))

# ── Animation state ──────────────────────────────────
alpha = [0.0]
pos_x = [start_x]
dur_ms = int({duration} * 1000)
elapsed = [0]

def slide_in():
    alpha[0] = min(alpha[0] + 0.12, 0.96)
    root.attributes("-alpha", alpha[0])
    # Ease-out slide
    pos_x[0] = pos_x[0] + (final_x - pos_x[0]) * 0.25
    root.geometry(f"{{width}}x{{height}}+{{int(pos_x[0])}}+{{y}}")
    if abs(pos_x[0] - final_x) > 1 or alpha[0] < 0.96:
        root.after(16, slide_in)
    else:
        pos_x[0] = final_x
        root.geometry(f"{{width}}x{{height}}+{{final_x}}+{{y}}")
        root.after(16, tick_progress)

def tick_progress():
    elapsed[0] += 16
    progress = max(0, 1.0 - elapsed[0] / dur_ms)
    bar_fill.place(relx=0, rely=0, relwidth=progress, relheight=1.0)
    if elapsed[0] >= dur_ms:
        slide_out()
    else:
        root.after(16, tick_progress)

def slide_out():
    alpha[0] -= 0.06
    pos_x[0] += 8
    if alpha[0] <= 0.0:
        root.destroy()
        return
    root.attributes("-alpha", alpha[0])
    root.geometry(f"{{width}}x{{height}}+{{int(pos_x[0])}}+{{y}}")
    root.after(16, slide_out)

def dismiss(event=None):
    root.destroy()

for w in [root, card, body, t, m, close_lbl, stripe]:
    w.bind("<Button-1>", dismiss)

root.after(30, slide_in)
root.mainloop()
'''
    try:
        startupinfo = None
        if sys.platform == "win32":
            startupinfo = subprocess.STARTUPINFO()
            startupinfo.dwFlags |= subprocess.STARTF_USESHOWWINDOW
            startupinfo.wShowWindow = 0

        subprocess.Popen(
            [sys.executable, "-c", script],
            startupinfo=startupinfo,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
    except Exception as e:
        print(f"[Notification] {title}: {message} (overlay failed: {e})")
