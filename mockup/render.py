#!/usr/bin/env python3
"""Makiety ekranu aplikacji i-MiEV (PNG) bez telefonu i emulatora.

Rysuje ten sam uklad co aplikacja: cztery zakladki (START, OGNIWA, ODCINEK, INNE),
tarcza mocy w wariancie "zero w 1/3" oraz siatka 88 ogniw (4 kolumny w pionie,
8 w poziomie).

Uzycie:
    python3 render.py            # wszystkie zakladki + porownanie + widok poziomy
    python3 render.py start      # tylko jedna zakladka

Wyniki: screen-start.png, screen-cells.png, screen-trip.png, screen-other.png,
        screen-cells-landscape.png, screen-zakladki.png, screen-porownanie.png
"""
import math
import subprocess
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

HERE = Path(__file__).parent

REGEN_MIN = -25.0
DRAW_MAX = 50.0
SWEEP = 270.0
CX, W, HEIGHT = 150.0, 300, 330
CY, R = HEIGHT / 2, 138.0

# (zero_angle, regen_sweep, draw_sweep, nazwa, opis)
# (zero_angle, regen_sweep, draw_sweep, gap, nazwa, opis)
PRESETS = {
    "third": (270.0, 90.0, 180.0, 0.0, "Zero na 12:00, 1/3 w lewo",
              "reku 90° · moc 180° · skala liniowa 3,6°/kW"),
    "half": (270.0, 135.0, 135.0, 0.0, "Zero na 12:00, po połowie",
             "po 135° · reku 5,4°/kW, moc 2,7°/kW"),
    "left": (180.0, 90.0, 180.0, 0.0, "Zero na 9:00 (liniowo)",
             "reku 90° do 6:00 · moc 180° przez 12:00 do 3:00"),
    "full": (180.0, 90.0, 270.0, 12.0, "Zero na 9:00, pełne koło",
             "reku 90° · moc 270° · przerwa 12° na 6:00"),
}

TABS = [("START", "start"), ("OGNIWA", "cells"), ("ODCINEK", "trip"), ("INNE", "other")]


# --------------------------------------------------------------------------- tarcza

def angle_of(v: float, preset) -> float:
    zero, regen, draw, gap = preset[0], preset[1], preset[2], preset[3]
    if v < 0:
        t = min(1.0, -v / -REGEN_MIN)
        return zero - (regen - gap / 2) * t
    t = min(1.0, v / DRAW_MAX)
    return zero + (draw - gap / 2) * t


def point(angle_deg: float, radius: float):
    a = math.radians(angle_deg)
    return CX + radius * math.cos(a), CY + radius * math.sin(a)


def arc(start_deg: float, end_deg: float, radius: float, color: str, width: float,
        cap: str = "butt", extra: str = "") -> str:
    x1, y1 = point(start_deg, radius)
    x2, y2 = point(end_deg, radius)
    span = end_deg - start_deg
    large = 1 if abs(span) > 180 else 0
    direction = 1 if span >= 0 else 0
    return (f'<path d="M {x1:.1f} {y1:.1f} A {radius:.1f} {radius:.1f} 0 {large} {direction} '
            f'{x2:.1f} {y2:.1f}" fill="none" stroke="{color}" stroke-width="{width}" '
            f'stroke-linecap="{cap}"{extra}/>')


def gauge_svg(value: float, preset, height: int = 330) -> str:
    zero, regen, draw, gap = preset[0], preset[1], preset[2], preset[3]
    regen_span, draw_span = regen - gap / 2, draw - gap / 2
    total = regen_span + draw_span
    start = zero - regen_span
    a = lambda v: angle_of(v, preset)  # noqa: E731

    parts = [f'<svg class="gauge" width="100%" viewBox="0 0 300 {height}">',
             '<defs><filter id="glow"><feGaussianBlur stdDeviation="3.5" result="b"/>'
             '<feMerge><feMergeNode in="b"/><feMergeNode in="SourceGraphic"/></feMerge>'
             '</filter></defs>',
             arc(start, start + total, R, "#232C38", 15, "round")]

    for lo, hi, color in [(REGEN_MIN, -8, "#1B3446"), (0, 18, "#1F3A2C"),
                          (18, 32, "#3A3020"), (32, DRAW_MAX, "#3A2222")]:
        parts.append(arc(a(lo), a(hi), R, color, 8))

    for v in range(-25, 51, 5):
        if v in (-25, 50):          # skrajne kreski usuniete - schodza sie na 6:00
            continue
        if v % 10 != 0:             # etykiety i kreski tylko co 10 kW po obu stronach
            continue
        ang = a(float(v))
        x1, y1 = point(ang, R - 12)
        x2, y2 = point(ang, R - 5)
        parts.append(f'<line x1="{x1:.1f}" y1="{y1:.1f}" x2="{x2:.1f}" y2="{y2:.1f}" '
                     f'stroke="#4A5766" stroke-width="2"/>')
        if True:                    # etykiety wszystkich pozostalych kresek
            lx, ly = point(ang, R - 26)
            parts.append(f'<text x="{lx:.1f}" y="{ly:.1f}" fill="#A7B2BE" font-size="11" '
                         f'font-family="DejaVu Sans" text-anchor="middle" '
                         f'dominant-baseline="middle">{v}</text>')

    if value < 0:
        parts.append(arc(a(value), zero, R, "#5BC8FF", 15, "round", ' filter="url(#glow)"'))
    else:
        parts.append(arc(zero, a(value), R, "#57D7A5", 15, "round", ' filter="url(#glow)"'))

    ang = a(value)
    nx1, ny1 = point(ang, R * 0.45)
    nx2, ny2 = point(ang, R * 0.90)
    parts.append(f'<line x1="{nx1:.1f}" y1="{ny1:.1f}" x2="{nx2:.1f}" y2="{ny2:.1f}" '
                 f'stroke="#EAEEF2" stroke-width="4" stroke-linecap="round"/>')
    whole = int(abs(value))
    frac = f"{abs(value) - whole:.1f}"[2:]
    sign = "-" if value < 0 else ""
    parts.append(f'<text x="{CX}" y="{CY + 38}" fill="#EAEEF2" font-size="50" '
                 f'font-weight="bold" font-family="DejaVu Sans" text-anchor="middle">'
                 f'{sign}{whole}<tspan font-size="31">,{frac}</tspan></text>')
    parts.append(f'<text x="{CX}" y="{CY + 56}" fill="#8B98A5" font-size="15" '
                 f'font-family="DejaVu Sans" text-anchor="middle">kW</text>')
    parts.append('</svg>')
    return "".join(parts)


def spark_svg() -> str:
    values = [2, 6, 9, 14, 11, 6, -2, -6, -9, -5, 0, 4, 9, 13, 8, 2, -3, -7,
              -4, 1, 6, 11, 16, 12, 5, -1, -5, -8, -3, 2, 3, 2, 1, 0, 0]
    w, h = 300, 54
    lo, hi = REGEN_MIN, DRAW_MAX
    y_zero = h * (hi / (hi - lo))
    pts = [f"{w * i / (len(values) - 1):.1f} "
           f"{h * (1 - (max(lo, min(hi, v)) - lo) / (hi - lo)):.1f}"
           for i, v in enumerate(values)]
    line = "M " + " L ".join(pts)
    area = f"M 0 {y_zero:.1f} L " + " L ".join(pts) + f" L {w} {y_zero:.1f} Z"
    return (f'<svg width="{w}" height="{h}" viewBox="0 0 {w} {h}">'
            f'<line x1="0" y1="{y_zero:.1f}" x2="{w}" y2="{y_zero:.1f}" stroke="#2A333F"/>'
            f'<path d="{area}" fill="#57D7A5" fill-opacity="0.16"/>'
            f'<path d="{line}" fill="none" stroke="#57D7A5" stroke-width="2"/></svg>')


# --------------------------------------------------------------------------- ogniwa

def cells_data():
    """88 ogniw: lekkie rozproszenie + jedno najslabsze i jedno najmocniejsze."""
    cells = []
    for i in range(88):
        base = 4096 + (i % 7) - 3
        cells.append(base)
    cells[37] = 4086          # najslabsze
    cells[12] = 4104          # najmocniejsze
    return cells


def cells_grid_html(columns: int) -> str:
    cells = cells_data()
    lo, hi = min(cells), max(cells)
    rows = []
    for r in range(0, len(cells), columns):
        chunk = cells[r:r + columns]
        cells_html = []
        for offset, mv in enumerate(chunk):
            index = r + offset
            color = "#FFB057" if mv == lo else "#57D7A5" if mv == hi else "#EAEEF2"
            fraction = max(0.02, min(1.0, (mv - 3800) / 400))
            bar_color = "#FFB057" if mv == lo else "#57D7A5"
            cells_html.append(f"""
        <div class="cell">
          <div class="cell-mod">m{index // 4 + 1}</div>
          <div class="cell-mv" style="color:{color}">{mv}</div>
          <div class="cell-bar"><div style="width:{fraction * 100:.0f}%;background:{bar_color}"></div></div>
        </div>""")
        rows.append('<div class="cell-row">' + "".join(cells_html) + "</div>")
    return "".join(rows)


# --------------------------------------------------------------------------- szklo

CSS = """
@page { size: 400pt 860pt; margin: 0; }
* { box-sizing: border-box; }
body { margin: 0; padding: 10pt 14pt 0; background: #0B0E12; color: #EAEEF2;
       font-family: "DejaVu Sans", sans-serif; height: 860pt; position: relative; }
.bar { display: flex; align-items: center; justify-content: space-between; }
.spinner { font-size: 10pt; background: #10151A; border: 0.75pt solid #212934;
           border-radius: 6pt; padding: 4pt 8pt; width: 62%; }
.btn-primary { background: #57D7A5; color: #0B0E12; font-weight: bold; font-size: 9pt;
               padding: 5pt 11pt; border-radius: 8pt; }
.status { color: #8B98A5; font-size: 8pt; margin: 4pt 0 8pt; }
.card { background: #151A21; border: 0.75pt solid #212934; border-radius: 12pt;
        padding: 9pt; margin-bottom: 7pt; }
.title { color: #8B98A5; font-size: 7pt; letter-spacing: 1.2pt; margin-bottom: 7pt; }
.chips { display: flex; margin-bottom: 7pt; }
.chip { flex: 1; background: #12171D; border: 0.75pt solid #1C232C; border-radius: 9pt;
        padding: 8pt; }
.chip + .chip { margin-left: 5pt; }
.chip .lbl { color: #8B98A5; font-size: 6.5pt; letter-spacing: 1pt; }
.chip .val { font-size: 16pt; font-weight: bold; margin-top: 2pt; }
.cols { display: flex; }
.col { flex: 1; }
.col .lbl { color: #A7B2BE; font-size: 6.5pt; letter-spacing: 1pt; }
.col .val { font-size: 11pt; font-weight: bold; margin-top: 2pt; }
.foot { color: #6C7A89; font-size: 7pt; margin-top: 6pt; }
.center { text-align: center; }
.peak { color: #5F6B78; font-size: 7.5pt; text-align: center; margin: 1pt 0 5pt; }
svg.gauge { width: 100%; height: auto; }
.spark-label { color: #5F6B78; font-size: 6pt; letter-spacing: 1pt; text-align: left; }
.cell-row { display: flex; margin-bottom: 3pt; }
.cell { flex: 1; text-align: center; margin: 0 1.5pt; }
.cell-mod { color: #8B98A5; font-size: 6.5pt; }
.cell-mv { font-size: 9pt; font-weight: bold; }
.cell-bar { height: 2.5pt; background: #212934; margin-top: 1pt; border-radius: 2pt;
            overflow: hidden; }
.cell-bar div { height: 100%; }
.hist-row { display: flex; justify-content: space-between; align-items: center;
            padding: 5pt 0; border-bottom: 0.5pt solid #212934; }
.hist-when { font-size: 8pt; font-weight: bold; }
.hist-det { color: #8B98A5; font-size: 6.5pt; margin-top: 1pt; }
.hist-per { color: #57D7A5; font-size: 11pt; font-weight: bold; text-align: right; }
.hist-unit { color: #5F6B78; font-size: 5.5pt; text-align: right; }
.row { display: flex; justify-content: space-between; padding: 2pt 0; font-size: 8pt; }
.row .k { color: #8B98A5; }
.row .v { font-weight: bold; }
.buttons { display: flex; margin-top: 6pt; }
.btn { flex: 1; background: #232B36; color: #EAEEF2; text-align: center; padding: 8pt;
       border-radius: 9pt; font-size: 8pt; }
.btn + .btn { margin-left: 5pt; }
.btn-wide { background: #232B36; color: #8B98A5; text-align: center; padding: 8pt;
            border-radius: 9pt; font-size: 8pt; margin-top: 6pt; }
.tabbar { position: absolute; left: 0; right: 0; bottom: 0; height: 48pt;
          background: #0E1218; border-top: 0.75pt solid #212934; display: flex; }
.tab { flex: 1; text-align: center; padding-top: 14pt; font-size: 8pt;
       letter-spacing: 1pt; color: #5F6B78; }
.tab.active { color: #57D7A5; font-weight: bold; background: #151A21; }
"""


def tabbar(active: str) -> str:
    items = "".join(
        f'<div class="tab{" active" if key == active else ""}">{label}</div>'
        for label, key in TABS)
    return f'<div class="tabbar">{items}</div>'


def header(power_kw: float = 0.0, regen_sweep=None) -> str:
    return f"""
<div class="bar">
  <div class="spinner">V-LINK [86:DC:85:AB:F7:F1]</div>
  <div class="btn-primary">Rozłącz</div>
</div>
<div class="status">Czytam dane</div>
"""


def tab_start(power: float, preset) -> str:
    return header() + f"""
<div class="card center">
  {gauge_svg(power, preset)}
  <div class="peak">szczyt 18 kW · rekuperacja −25 kW · pobór 50 kW</div>
  <div class="spark-label">OSTATNIE 2 MIN</div>
  {spark_svg()}
</div>

<div class="card">
  <div class="title">NAPIĘCIA OGNIW</div>
  <div class="cols">
    <div class="col"><div class="lbl">MIN</div><div class="val">4086 mV</div></div>
    <div class="col"><div class="lbl">MAX</div><div class="val">4104 mV</div></div>
    <div class="col"><div class="lbl">ROZRZUT</div><div class="val" style="color:#FFB057">18 mV</div></div>
  </div>
  <div class="foot">Pakiet 360.6 V / 6.8 A · ogniw 88</div>
</div>

<div class="card">
  <div class="title">TEMPERATURY BATERII</div>
  <div class="cols">
    <div class="col"><div class="lbl">MIN</div><div class="val">10°</div></div>
    <div class="col"><div class="lbl">MAX</div><div class="val">16°</div></div>
    <div class="col"><div class="lbl">RÓŻNICA</div><div class="val" style="color:#FFB057">6 K</div></div>
  </div>
  <div class="foot">Czujników 66 · temperatury baterii</div>
</div>
"""


def tab_cells(columns: int = 4) -> str:
    cells = cells_data()
    lo, hi = min(cells), max(cells)
    return header() + f"""
<div class="card">
  <div class="title">WSZYSTKIE OGNIWA</div>
  <div class="cols">
    <div class="col"><div class="lbl">MIN</div><div class="val">4086 mV</div></div>
    <div class="col"><div class="lbl">MAX</div><div class="val">4104 mV</div></div>
    <div class="col"><div class="lbl">ROZRZUT</div><div class="val" style="color:#FFB057">{hi - lo} mV</div></div>
  </div>
  <div class="foot">Ogniw 88 · najsłabsze m10 (4086 mV) · Δ {hi - lo} mV<br/>pasek: 3800–4200 mV</div>
</div>

<div class="card">
  {cells_grid_html(columns)}
</div>
"""


def tab_trip(power: float = 0.0) -> str:
    return header() + """
<div class="card">
  <div class="title">TEN ODCINEK</div>
  <div class="cols">
    <div class="col"><div class="lbl">DYSTANS</div><div class="val">11.4 km</div></div>
    <div class="col"><div class="lbl">ENERGIA</div><div class="val">2.12 kWh</div></div>
    <div class="col"><div class="lbl">NA 100 KM</div><div class="val">18.6</div></div>
  </div>
  <div class="cols" style="margin-top:8pt">
    <div class="col"><div class="lbl">CZAS</div><div class="val">14:32</div></div>
    <div class="col"><div class="lbl">ŚREDNIA</div><div class="val">47</div></div>
    <div class="col"><div class="lbl">REKUP.</div><div class="val">0.24 kWh</div></div>
  </div>
  <div class="foot">Szczyt 18 kW · grzanie 0.40 kWh · SoC 81→74%</div>
</div>

<div class="card">
  <div class="title">HISTORIA JAZD</div>
  <div class="hist-row">
    <div><div class="hist-when">dziś 18:41 · 11.4 km</div>
      <div class="hist-det">2.12 kWh netto · rekuperacja 0.24 · 15 min · SoC 81→74%</div></div>
    <div><div class="hist-per">18.6</div><div class="hist-unit">kWh/100</div></div>
  </div>
  <div class="hist-row">
    <div><div class="hist-when">dziś 09:12 · 9.4 km</div>
      <div class="hist-det">1.65 kWh netto · rekuperacja 0.17 · 12 min · SoC 66→55%</div></div>
    <div><div class="hist-per">17.5</div><div class="hist-unit">kWh/100</div></div>
  </div>
  <div class="hist-row" style="border-bottom:none">
    <div><div class="hist-when">wczoraj 17:30 · 2.9 km</div>
      <div class="hist-det">0.66 kWh netto · rekuperacja 0.06 · 6 min · SoC 58→53%</div></div>
    <div><div class="hist-per">22.9</div><div class="hist-unit">kWh/100</div></div>
  </div>
</div>
"""


def tab_other(power: float = 0.0) -> str:
    return header() + """<div class="chips">
  <div class="chip"><div class="lbl">SOC</div><div class="val">81.5%</div></div>
  <div class="chip"><div class="lbl">PRĘDKOŚĆ</div><div class="val">47</div></div>
  <div class="chip"><div class="lbl">GRZANIE PTC</div><div class="val">2.5</div></div>
</div>



<div class="card">
  <div class="title">POZOSTAŁE</div>
  <div class="row"><span class="k">Zasięg</span><span class="v">103 km</span></div>
  <div class="row"><span class="k">Licznik</span><span class="v">116229 km</span></div>
  <div class="row"><span class="k">Płyn grzewczy</span><span class="v">99 → 51 °C (Δ 48 K)</span></div>
  <div class="row"><span class="k">Klima</span><span class="v">0.34 kW</span></div>
  <div class="row"><span class="k">Bufor próbek</span><span class="v">1284</span></div>
  <div class="row"><span class="k">Ogniwa</span><span class="v">4086–4104 mV, 88</span></div>
</div>

<div class="buttons">
  <div class="btn">Eksport CSV</div>
  <div class="btn">Wyczyść bufor</div>
</div>
<div class="btn-wide">Ekran może gasnąć</div>
"""


def page(body: str, active_tab: str, landscape: bool = False) -> str:
    page_css = ("@page { size: 700pt 360pt; margin: 0; }"
                if landscape else "@page { size: 400pt 860pt; margin: 0; }")
    extra = ("body { height: 360pt; }" if landscape else "")
    return f"""<!doctype html>
<html lang="pl"><head><meta charset="utf-8"><title>i-MiEV — makieta</title>
<style>{CSS}{page_css}{extra}</style></head><body>
{body}
{tabbar(active_tab)}
</body></html>
"""


def render(name: str, html: str, landscape: bool = False) -> Path:
    html_path = HERE / f"{name}.html"
    html_path.write_text(html, encoding="utf-8")
    subprocess.run(["weasyprint", html_path.name, f"{name}.pdf"], cwd=HERE, check=True)
    dpi = "140" if landscape else "170"
    subprocess.run(["pdftoppm", "-r", dpi, "-png", "-singlefile",
                    f"{name}.pdf", name], cwd=HERE, check=True)
    return HERE / f"{name}.png"


def compose(paths, out_name: str, labels, per_row: int = 4, gap: int = 20,
            sublabels=None) -> Path:
    images = [Image.open(p) for p in paths]
    header, gap = 70 if sublabels else 52, gap
    rows = [images[i:i + per_row] for i in range(0, len(images), per_row)]
    width = max(sum(i.width for i in row) + gap * (len(row) - 1) for row in rows) + 2 * gap
    height = header + sum(max(i.height for i in row) for row in rows) + gap * len(rows)
    canvas = Image.new("RGB", (width, height), "#05070A")
    draw = ImageDraw.Draw(canvas)
    try:
        font = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf", 22)
        small = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", 15)
    except OSError:
        font = small = ImageFont.load_default()

    y = 12
    idx = 0
    for row in rows:
        x = gap
        for img in row:
            canvas.paste(img, (x, y + header))
            draw.text((x + 4, y + 8), labels[idx], fill="#57D7A5", font=font)
            if sublabels:
                draw.text((x + 4, y + 34), sublabels[idx], fill="#8B98A5", font=small)
            x += img.width + gap
            idx += 1
        y += header + max(i.height for i in row) + gap

    out = HERE / out_name
    canvas.save(out)
    return out


def main() -> None:
    wanted = sys.argv[1:] or ["start", "cells", "trip", "other"]
    power = 2.5
    default_preset = PRESETS["full"]

    pages = {"start": tab_start(power, default_preset), "cells": tab_cells(4),
             "trip": tab_trip(power), "other": tab_other(power)}

    for name in wanted:
        path = render(f"screen-{name}", page(pages[name], name))
        print(f"gotowe: {path}")

    if all(name in wanted for name in ("start", "cells", "trip", "other")):
        compose([HERE / f"screen-{n}.png" for n in ("start", "cells", "trip", "other")],
                "screen-zakladki.png",
                ["1. START", "2. OGNIWA", "3. ODCINEK", "4. INNE"])

    if "cells" in wanted:
        path = render("screen-cells-landscape", page(tab_cells(8), "cells", landscape=True),
                      landscape=True)
        print(f"gotowe: {path}")

    if "start" in wanted:
        paths, labels = [], []
        for key, preset in PRESETS.items():
            path = render(f"screen-gauge-{key}", page(tab_start(power, preset), "start"))
            paths.append(path)
            labels.append(preset[4])
            print(f"gotowe: {path}")
        compare = compose(paths, "screen-zegary.png",
                          labels, per_row=4, gap=16, sublabels=[p[5] for p in PRESETS.values()])
        print(f"porownanie: {compare}")


if __name__ == "__main__":
    main()
