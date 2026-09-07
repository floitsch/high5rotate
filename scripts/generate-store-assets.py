#!/usr/bin/env python3
# Copyright (C) 2026 Toit contributors.
"""Export the app's vector icon and a feature graphic; requires rsvg-convert and Pillow."""

import html
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET

from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "store/assets"
ASSETS.mkdir(parents=True, exist_ok=True)
ANDROID = "{http://schemas.android.com/apk/res/android}"
colors = {
    item.attrib["name"]: item.text
    for item in ET.parse(ROOT / "app/src/main/res/values/colors.xml").getroot()
}
vector = ET.parse(ROOT / "app/src/main/res/drawable/ic_launcher.xml").getroot()
paths = []
for item in vector:
    color = item.attrib[ANDROID + "fillColor"]
    if color.startswith("@color/"):
        color = colors[color.removeprefix("@color/")]
    paths.append(f'<path fill="{color}" d="{item.attrib[ANDROID + "pathData"]}"/>')

icon = '<svg xmlns="http://www.w3.org/2000/svg" width="512" height="512" viewBox="0 0 108 108">' + "".join(paths) + "</svg>"
(ASSETS / "icon.svg").write_text(icon + "\n")

feature = '''<svg xmlns="http://www.w3.org/2000/svg" width="1024" height="500" viewBox="0 0 1024 500">
<rect width="1024" height="500" fill="#231942"/>
<circle cx="945" cy="20" r="215" fill="#39265C"/>
<circle cx="10" cy="515" r="185" fill="#39265C"/>
<g font-family="DejaVu Sans, sans-serif">
<text x="92" y="190" fill="#FFB703" font-size="67" font-weight="bold">High 5 Rotate</text>
<text x="94" y="252" fill="#FFFFFF" font-size="31">High five. Rotate. Keep dancing.</text>
<text x="94" y="297" fill="#EBDDFF" font-size="25">Partner rotation cues, timed to your music.</text>
</g>
<g fill="#FFB703">
<circle cx="119" cy="369" r="11"/><circle cx="367" cy="369" r="11"/>
<circle cx="615" cy="369" r="11"/><circle cx="863" cy="369" r="11"/>
</g>
<path d="M139 369H347M387 369H595M635 369H843" stroke="#EBDDFF" stroke-width="4" stroke-linecap="round"/>
</svg>'''
(ASSETS / "feature-graphic.svg").write_text(feature + "\n")
for stem in ("icon", "feature-graphic"):
    png = ASSETS / f"{stem}.png"
    subprocess.run(["rsvg-convert", str(ASSETS / f"{stem}.svg"), "-o", str(png)], check=True)
    with Image.open(png) as image:
        image.convert("RGBA" if stem == "icon" else "RGB").save(png)

policy = (ROOT / "app/src/main/res/raw/privacy_policy.txt").read_text()
paragraphs = policy.strip().split("\n\n")
policy_html = "\n".join(f"<p>{html.escape(p).replace(chr(10), '<br>')}</p>" for p in paragraphs)
docs = ROOT / "docs"
docs.mkdir(exist_ok=True)
(docs / ".nojekyll").touch()
(docs / "privacy.html").write_text(f'''<!doctype html>
<html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>High 5 Rotate privacy policy</title>
<style>body{{font:18px/1.65 system-ui,sans-serif;max-width:760px;margin:48px auto;padding:0 24px;background:#fff8f1;color:#231942}}a{{color:#6d3fc0}}</style>
<main><h1>High 5 Rotate privacy policy</h1>{policy_html}
<p><a href="https://github.com/floitsch/high5rotate/issues">Contact the project</a> · <a href="./">High 5 Rotate</a></p></main></html>
''')
print("Exported icon, feature graphic, and public privacy page.")
