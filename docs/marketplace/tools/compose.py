"""Builds the Marketplace slides from the captures: one HTML page per slide, rendered by headless
Chrome at 2x into docs/marketplace/.

Every capture in build/marketplace/shots/raw is painted at 2x of its logical size, so crops are
given in logical pixels (x, y, w, h) and scaled with `s` (CSS px per logical px).

Rule for every slide: each picture is a whole thing (a window, a tool window island, a dialog, a
popup) and sits inside the slide with a margin. Nothing runs off the edge, and a crop only ever
goes through empty space, never through a line of text.

    python3 docs/marketplace/tools/compose.py [slide-name-part...]
"""
import os
import subprocess
import sys

TOOLS = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(TOOLS)))
W_DIR = os.path.join(ROOT, "build", "marketplace")
RAW = os.path.join(W_DIR, "shots", "raw")
HTML = os.path.join(W_DIR, "slides")
OUT = os.path.join(ROOT, "docs", "marketplace")
ICON = os.path.join(ROOT, "src", "main", "resources", "META-INF", "pluginIcon_dark.svg")
CHROME = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
W, H = 1200, 760
M = 56  # side margin

PURPLE, GREEN, BLUE, ORANGE = "#ab7df8", "#3fb950", "#3574f0", "#e2705f"

os.makedirs(HTML, exist_ok=True)


def png_size(path):
    with open(path, "rb") as f:
        head = f.read(24)
    return int.from_bytes(head[16:20], "big"), int.from_bytes(head[20:24], "big")


def load_geometry():
    out = {}
    path = os.path.join(W_DIR, "shots", "geometry.txt")
    for line in open(path):
        parts = line.split()
        if len(parts) == 5:
            out[parts[0]] = tuple(int(float(v)) for v in parts[1:])
    return out


def load_popups():
    """Owned popups per shot, from capture.log: {shot: [(x, y, w, h), ...]} in window coordinates."""
    out, pending = {}, []
    for line in open(os.path.join(W_DIR, "shots", "capture.log")):
        parts = line.split()
        if len(parts) >= 6 and parts[1] == "+" and parts[2] == "popup":
            x, y = (int(v) for v in parts[5].split(","))
            w, h = (int(v) for v in parts[6].split("x"))
            pending.append((x, y, w, h))
        elif len(parts) >= 3 and parts[1] == "shot":
            out[parts[2]] = pending
            pending = []
    return out


GEOM = load_geometry()
POPUPS = load_popups()


def raw(name):
    return os.path.join(RAW, name + ".png")


def size(name):
    pw, ph = png_size(raw(name))
    return pw / 2, ph / 2


def shot(name, x=None, y=None, w=None, h=None, s=1.0, left=0, top=0, lights=False, radius=12, extra=""):
    """An absolutely placed piece of a capture: [x, y, w, h] in logical px, drawn at scale s."""
    lw, lh = size(name)
    x = 0 if x is None else x
    y = 0 if y is None else y
    w = lw - x if w is None else w
    h = lh - y if h is None else h
    traffic = ""
    if lights and x == 0 and y == 0:
        traffic = "".join(
            f'<i style="left:{cx * s - 6 * s}px;top:{20 * s - 6 * s}px;width:{12 * s}px;height:{12 * s}px;background:{c}"></i>'
            for cx, c in ((20, "#ff5f57"), (40, "#febc2e"), (60, "#28c840"))
        )
    return (
        f'<div class="shot" style="left:{left}px;top:{top}px;width:{w * s}px;height:{h * s}px;border-radius:{radius}px;{extra}">'
        f'<img src="file://{raw(name)}" style="width:{lw * s}px;height:{lh * s}px;left:{-x * s}px;top:{-y * s}px">'
        f"{traffic}</div>"
    )


def dialog(name, title, s, left, top, w=None, h=None):
    """A dialog with the macOS title bar Swing does not paint. [top] is where the title bar starts."""
    lw, lh = size(name)
    w = lw if w is None else w
    h = lh if h is None else h
    bar = 28 * s
    dots = "".join(
        f'<i style="left:{(12 + i * 20) * s}px;top:{8 * s}px;width:{12 * s}px;height:{12 * s}px;background:{c}"></i>'
        for i, c in enumerate(("#ff5f57", "#febc2e", "#28c840"))
    )
    return (
        f'<div class="bar" style="left:{left}px;top:{top}px;width:{w * s}px;height:{bar}px;line-height:{bar}px;font-size:{13 * s}px">'
        f"{dots}<span>{title}</span></div>"
        + shot(name, 0, 0, w, h, s=s, left=left, top=top + bar, radius=0, extra=f"border-radius:0 0 {12 * s}px {12 * s}px")
    )


def island(top_to=None, pad=3):
    """The Upstack tool window island: the decorator is [pad] px larger on every side. [top_to]:
    where the island's content ends, for a crop through its empty lower part."""
    x, y, w, h = GEOM["toolwindow"]
    x, y, w, h = x + pad, y + pad, w - 2 * pad, h - 2 * pad
    if top_to is not None:
        h = top_to - y
    return x, y, w, h


def page(slide, body):
    icon = open(ICON).read() if os.path.exists(ICON) else ""
    glow = slide.get("glow", (PURPLE, GREEN))
    center = slide.get("center", False)
    brand_pos = "left:0;right:0;justify-content:center" if center else f"left:{M}px"
    return f"""<!doctype html><html><head><meta charset="utf-8"><style>
html,body{{margin:0;width:{W}px;height:{H}px;overflow:hidden}}
body{{font-family:-apple-system,BlinkMacSystemFont,"SF Pro Display","Helvetica Neue",sans-serif;color:#f3f4f6;
  background:
    radial-gradient(900px 520px at 88% -10%, {glow[0]}38, transparent 70%),
    radial-gradient(800px 600px at -10% 110%, {glow[1]}33, transparent 70%),
    linear-gradient(160deg,#16181d 0%,#0f1013 100%);position:relative}}
.brand{{position:absolute;{brand_pos};top:36px;display:flex;align-items:center;gap:10px;font-size:15px;font-weight:600;color:#c9ccd3;letter-spacing:.2px}}
.brand svg{{width:22px;height:22px}}
h1{{position:absolute;margin:0;font-size:{slide.get("size", 40)}px;line-height:1.12;font-weight:700;letter-spacing:-.6px}}
p.sub{{position:absolute;margin:0;font-size:18px;line-height:1.5;color:#a6adbb}}
p.sub b{{color:#e8eaee;font-weight:600}}
code,.key{{font-family:"JetBrains Mono",ui-monospace,Menlo,monospace;font-size:.84em;color:#e8eaee;background:#ffffff14;padding:1px 6px;border-radius:6px}}
.shot{{position:absolute;overflow:hidden;box-shadow:0 30px 80px #000000b0,0 0 0 1px #ffffff17}}
.shot img{{position:absolute;max-width:none}}
.shot i,.bar i{{position:absolute;border-radius:50%}}
.bar{{position:absolute;background:#2b2d30;border-radius:12px 12px 0 0;box-shadow:0 0 0 1px #ffffff17;font-weight:600;color:#c9ccd3;text-align:center;z-index:1}}
.legend{{position:absolute}}
.legend div{{display:flex;align-items:center;gap:14px;margin-bottom:14px}}
.legend .key{{min-width:72px;text-align:center;font-size:13px;padding:5px 10px;border-radius:8px;white-space:nowrap}}
.legend span:last-child{{font-size:16px;color:#c3c8d2;line-height:1.35}}
.caption{{position:absolute;font-size:13px;font-weight:600;letter-spacing:.6px;text-transform:uppercase;color:#8b93a3}}
.bullets div{{display:flex;gap:12px;margin-bottom:14px;font-size:17px;color:#c3c8d2;line-height:1.45}}
.bullets b{{color:{PURPLE};font-weight:700}}
.bullets code{{font-size:.8em}}
</style></head><body>
<div class="brand">{icon}<span>Upstack</span></div>
{body}
</body></html>"""


def render(name, slide, body):
    html = os.path.join(HTML, name + ".html")
    with open(html, "w") as f:
        f.write(page(slide, body))
    out = os.path.join(OUT, name + ".png")
    subprocess.run([
        CHROME, "--headless=new", "--disable-gpu", "--hide-scrollbars", "--allow-file-access-from-files",
        f"--user-data-dir={os.path.join(W_DIR, 'chrome-profile')}", "--force-device-scale-factor=2",
        f"--window-size={W},{H}", f"--screenshot={out}", "file://" + html,
    ], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    print("slide", out)


def header_center(title, sub, top=74):
    return (f'<h1 style="left:{M}px;right:{M}px;top:{top}px;text-align:center">{title}</h1>'
            f'<p class="sub" style="left:{M + 40}px;right:{M + 40}px;top:{top + 52}px;text-align:center">{sub}</p>')


def header_side(title, sub, left, width, top, title_lines=2, after="", sub_gap=18):
    """A text column: title, subtitle and whatever comes after, from [top] down."""
    sub_top = top + title_lines * 45 + sub_gap
    return (f'<h1 style="left:{left}px;top:{top}px;width:{width}px">{title}</h1>'
            f'<p class="sub" style="left:{left}px;top:{sub_top}px;width:{width}px">{sub}</p>' + after)


def legend(rows, left, top, width=None):
    items = "".join(f'<div><span class="key">{k}</span><span>{v}</span></div>' for k, v in rows)
    style = f"left:{left}px;top:{top}px" + (f";width:{width}px" if width else "")
    return f'<div class="legend" style="{style}">{items}</div>'


def caption(text, left, top):
    return f'<div class="caption" style="left:{left}px;top:{top}px">{text}</div>'


def bullets(rows, left, top, width):
    items = "".join(f"<div><b>●</b><span>{t}</span></div>" for t in rows)
    return f'<div class="bullets" style="position:absolute;left:{left}px;top:{top}px;width:{width}px">{items}</div>'


def frame_slide(name, title, sub, top=176, bottom=36):
    """The whole IDE window, centred under a centred header."""
    fw, fh = size(name)
    s = min((H - top - bottom) / fh, (W - 2 * M) / fw)
    return header_center(title, sub) + shot(name, s=s, left=(W - fw * s) / 2, top=top, lights=True)


SLIDES = []


def slide(name, **kw):
    def wrap(fn):
        SLIDES.append((name, kw, fn))
        return fn
    return wrap


# 1 ---------------------------------------------------------------- hero
@slide("01-stacked-prs-in-your-ide", center=True)
def _():
    return frame_slide("01-hero", "Stacked pull requests, in your IDE",
                       "Every layer of the stack with its pull request, review, CI and labels. "
                       "Every button runs the <b>gh stack</b> command you would type.")


# 2 ---------------------------------------------------------------- glance
@slide("02-every-layer-at-a-glance", glow=(GREEN, PURPLE))
def _():
    x, y, w, h = island(top_to=470)
    s = 1.0
    top = (H - h * s) / 2
    right = M + w * s + 52
    col = W - M - right
    tip = [p for p in POPUPS.get("02-glance", []) if p[2] > 200][0]
    ts = 1.12
    after = caption("Hover a layer", right, 300) + shot("02-glance", *tip, s=ts, left=right, top=326, radius=10)
    return header_side("Every layer at a glance",
                       "Draft or ready, review, CI, size and labels, from the top layer down to the trunk. "
                       "A layer whose pull request targets the wrong base, or that GitHub cannot merge as is, says so.",
                       right, col, 96, title_lines=1, after=after) + \
        shot("02-glance-plain", x, y, w, h, s=s, left=M, top=top)


# 3 ---------------------------------------------------------------- start
@slide("03-start-a-stack", glow=(BLUE, GREEN))
def _():
    s = 0.9
    nw, nh = size("05-new-stack")
    aw, ah = size("06-add-layer")
    left_a = W - M - aw * s
    left_n = W - M - nw * s
    gap = 26
    total = (28 + nh) * s + gap + (28 + ah) * s
    top_n = (H - total) / 2
    top_a = top_n + (28 + nh) * s + gap
    text_w = left_a - M - 40
    after = legend((("New Stack…", "layers on any base branch, as a graph"),
                    ("Add Layer…", "on top, committing your changes")), M, 372, text_w)
    return header_side("Start a stack,<br>add layers", "Name the layers and see the stack before it exists. "
                       "The dialog shows the command it will run.", M, text_w, 150, after=after) + \
        dialog("05-new-stack", "New Stack", s, left_n, top_n) + \
        dialog("06-add-layer", "Add Layer", s, left_a, top_a)


# 4 ---------------------------------------------------------------- publish
@slide("04-publish-your-way", glow=(PURPLE, BLUE))
def _():
    dw, dh = size("07-publish")
    s = (H - 2 * 36 - 28) / dh
    left = W - M - dw * s
    top = 36
    text_w = left - M - 48
    after = legend((("Drafts", "push every layer, new pull requests as drafts"),
                    ("Ready", "the whole stack ready for review, after a preview"),
                    ("A layer", "publish one layer from its own menu")), M, 400, text_w)
    return header_side("Publish the stack your way",
                       "Choose layer by layer which pull requests are ready for review, and give the new ones "
                       "a title and a description.", M, text_w, 210, title_lines=1, after=after) + \
        dialog("07-publish", "Publish Stack", s, left, top)


# 5 ---------------------------------------------------------------- rebase
@slide("05-rebase-only-what-changed", glow=(ORANGE, PURPLE))
def _():
    x, y, w, h = island(top_to=510)
    s = 1.0
    top = (H - h * s) / 2
    right = M + w * s + 52
    col = W - M - right
    menu = POPUPS["12-rebase-menu"][0]
    ms = min(1.0, col / menu[2])
    after = caption("Or pick the rebase you need", right, 372) + \
        shot("12-rebase-menu", *menu, s=ms, left=right, top=398, radius=10)
    return header_side("Rebase only what changed",
                       "After a commit on a lower layer, <b>Rebase Upstack</b> carries it to the layers above "
                       "without fetching or touching main, so the only conflicts are the ones you made.",
                       right, col, 110, after=after) + \
        shot("12-needs-rebase", x, y, w, h, s=s, left=M, top=top)


# 6 ---------------------------------------------------------------- conflicts
@slide("06-conflicts-guided", glow=(ORANGE, BLUE), center=True)
def _():
    x, y, w, h = island(top_to=560)
    top = 186
    dw, dh = size("13-conflicts-dialog")
    gap = 36
    s = (W - 2 * M - gap) / (w + dw)
    s = min(s, (H - top - 36) / h)
    total = (w + dw) * s + gap
    left = (W - total) / 2
    return header_center("Conflicts, guided",
                         "When a rebase stops, the IDE's merge dialog opens on its own and the stack says "
                         "which layer and commit stopped.") + \
        shot("13-conflicts-frame", x, y, w, h, s=s, left=left, top=top) + \
        dialog("13-conflicts-dialog", "Conflicts", s, left + w * s + gap, top + 70)


# 7 ---------------------------------------------------------------- layer changes
@slide("07-review-a-layer-like-its-pr", glow=(GREEN, BLUE), center=True)
def _():
    return frame_slide("11-layer-changes", "Review a layer like its pull request",
                       "<b>Show Layer Changes</b>: the IDE diff of what a layer adds on top of the one below.")


# 8 ---------------------------------------------------------------- merge and close
@slide("08-merge-or-close-the-stack", glow=(PURPLE, GREEN), center=True)
def _():
    top = 176
    avail = H - top - 30
    mw, mh = size("09-merge")
    cw, ch = size("10-close")
    ms = (avail - 28) / mh
    cs = (avail - 28) / ch
    gap = 44
    total = mw * ms + gap + cw * cs
    left = (W - total) / 2
    return header_center("Merge the stack in one go",
                         "The chosen pull request and every one below it, or none. Or close the whole stack.") + \
        dialog("09-merge", "Merge Stack", ms, left, top) + \
        dialog("10-close", "Close Stack", cs, left + mw * ms + gap, top)


# 9 ---------------------------------------------------------------- help everywhere
@slide("09-every-option-explains-itself", glow=(BLUE, PURPLE))
def _():
    x, y, w, h = island(top_to=640)
    pops = POPUPS["03-layer-menu"]
    right_edge = max(p[0] + p[2] for p in pops)
    span = right_edge - x
    s = min((H - 2 * 50) / h, 690 / span)
    left = W - M - span * s
    top = (H - h * s) / 2
    pieces = shot("03-layer-menu", x, y, w, h, s=s, left=left, top=top)
    for px, py, pw, ph in pops:
        pieces += shot("03-layer-menu", px, py, pw, ph, s=s, left=left + (px - x) * s, top=top + (py - y) * s, radius=10)
    text_w = left - M - 44
    after = bullets(("Toolbar buttons, menu items, banners and dialog options.",
                     "With your real branch and pull request in each command.",
                     "The Log tab keeps every command and its output."), M, 370, text_w)
    return header_side("Every option says what it runs",
                       "Hover anything to see what it does and the exact command, so you can switch to the "
                       "terminal at any point.", M, text_w, 150, after=after) + pieces


# 10 --------------------------------------------------------------- setup
@slide("10-set-up-in-a-minute", glow=(GREEN, ORANGE))
def _():
    sw, sh = size("15-settings")
    lw, lh = size("16-login")
    top = 186
    gap = 36
    s = min((W - 2 * M - gap) / (sw + lw), (H - top - 36) / (sh + 28))
    total = (sw + lw) * s + gap
    left = (W - total) / 2
    return header_center("Set up in a minute",
                         "<b>Check</b> shows your gh, account, gh-stack version and whether the repository supports "
                         "stacks, with the fix next to what is missing. Log in without a terminal.") + \
        dialog("15-settings", "Upstack", s, left, top) + \
        dialog("16-login", "Log In to github.com", s, left + sw * s + gap, top + 90)


if __name__ == "__main__":
    only = set(sys.argv[1:])
    for name, kw, fn in SLIDES:
        if only and not any(o in name for o in only):
            continue
        render(name, kw, fn())
