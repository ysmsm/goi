# /// script
# requires-python = ">=3.10"
# dependencies = ["skia-pathops>=0.8"]
# ///
"""고이 워드마크(goi) SVG 생성기.

    uv run branding/build_logo.py            # branding/goi-logo.svg, goi-logo-dark.svg 를 다시 만든다

단위는 Pleos 워드마크 x10 기준이다(캡 200, x-height 152.7, 세로 획 30.2, 둥근 글자 가로 획 26.7).
- g: Pleos P를 좌우로 뒤집은 구조. 보울이 스템에 닫히는 자리(베이스라인)를 P와 같은
  45도 두 쐐기로 끊어, 위 조각(보울+윗 스템)과 아래 조각(디센더)이 한 점에서만 만난다.
- g-o: g 스템 윗면이 o의 윗 획으로 이어진다. 그 아래 g 스템과 o 사이 틈은 끊김 쐐기로 흘러내린다.
- i: 점은 원. 점만 강조색(goi-accent), 나머지는 goi-ink.
"""
from pathlib import Path as FsPath

from pathops import Path, PathOp, op

K = 0.575  # Pleos o 곡선 핸들 비율
YT, YB = 47.3, 200.0  # x-height 윗선, 베이스라인
O_W, O_F, O_FI = 153.0, 12.0, 11.4  # o 폭, 바깥/안쪽 윗면 평평한 길이
G_W = 150.0  # g 보울+스템 폭
G_TAIL_INSET = 14.0
GAP_GO = 16.0  # g 스템과 o 사이
GAP_OI = 26.0  # o와 i 사이
DOT_GAP = 19.3  # i 점 아래끝과 x-height 사이

WEIGHT = 36.0  # 세로 획 = i 굵기. Pleos 워드마크는 30.2
DOT_SCALE = 1.18  # i 점 지름 = 굵기 x DOT_SCALE. 수치를 맞추기보다 눈으로 봤을 때 i 획과 균형 잡혀 보이는 값


def set_weight(w):
    """획 굵기에 묶인 값을 모두 다시 정한다. 바깥 크기(x-height, 글자 폭)는 그대로 두고 안쪽으로 굵어진다."""
    global S, T, G_DESC, G_TAIL_RH, G_TAIL_RV, DOT_D, DOT_TOP
    d = w - 30.2
    S = w  # 세로 획
    T = w * 26.7 / 30.2  # 둥근 글자 위아래 획(Pleos 비율)
    G_DESC = 64.0 + d * 0.8  # 디센더 깊이
    G_TAIL_RH, G_TAIL_RV = 54.0 + d * 0.5, 42.0 + d * 0.9
    DOT_D = w * DOT_SCALE
    DOT_TOP = YT - DOT_GAP - DOT_D


set_weight(WEIGHT)

INK = "#000000"
ACCENT = "#0064FF"  # Pleos informative_positive (light)
# 다크 배경용(goi-logo-dark.svg). README처럼 <img>로 넣어 CSS 변수를 줄 수 없는 곳에 쓴다
INK_DARK = "#FFFFFF"
ACCENT_DARK = "#0082FF"  # Pleos informative_positive (dark)

U, D, I = PathOp.UNION, PathOp.DIFFERENCE, PathOp.INTERSECTION


def stadium(x0, y0, x1, y1, f):
    p = Path()
    cx, cy = (x0 + x1) / 2, (y0 + y1) / 2
    rx, ry = (x1 - x0 - f) / 2, (y1 - y0) / 2
    a, b = cx - f / 2, cx + f / 2
    p.moveTo(a, y0)
    p.lineTo(b, y0)
    p.cubicTo(b + K * rx, y0, x1, cy - K * ry, x1, cy)
    p.cubicTo(x1, cy + K * ry, b + K * rx, y1, b, y1)
    p.lineTo(a, y1)
    p.cubicTo(a - K * rx, y1, x0, cy + K * ry, x0, cy)
    p.cubicTo(x0, cy - K * ry, a - K * rx, y0, a, y0)
    p.close()
    return p


def left_round_rect(x0, y0, x1, y1, rx):
    p = Path()
    cy, ry = (y0 + y1) / 2, (y1 - y0) / 2
    a = x0 + rx
    p.moveTo(a, y0)
    p.lineTo(x1, y0)
    p.lineTo(x1, y1)
    p.lineTo(a, y1)
    p.cubicTo(a - K * rx, y1, x0, cy + K * ry, x0, cy)
    p.cubicTo(x0, cy - K * ry, a - K * rx, y0, a, y0)
    p.close()
    return p


def bottom_round_rect(x0, y0, x1, y1, rh, rv):
    p = Path()
    p.moveTo(x0, y0)
    p.lineTo(x1, y0)
    p.lineTo(x1, y1 - rv)
    p.cubicTo(x1, y1 - rv + K * rv, x1 - rh + K * rh, y1, x1 - rh, y1)
    p.lineTo(x0 + rh, y1)
    p.cubicTo(x0 + rh - K * rh, y1, x0, y1 - rv + K * rv, x0, y1 - rv)
    p.close()
    return p


def poly(pts):
    p = Path()
    p.moveTo(*pts[0])
    for q in pts[1:]:
        p.lineTo(*q)
    p.close()
    return p


def rect(x0, y0, x1, y1):
    return poly([(x0, y0), (x1, y0), (x1, y1), (x0, y1)])


def circle(cx, cy, r, k=0.5523):
    p = Path()
    p.moveTo(cx, cy - r)
    p.cubicTo(cx + k * r, cy - r, cx + r, cy - k * r, cx + r, cy)
    p.cubicTo(cx + r, cy + k * r, cx + k * r, cy + r, cx, cy + r)
    p.cubicTo(cx - k * r, cy + r, cx - r, cy + k * r, cx - r, cy)
    p.cubicTo(cx - r, cy - k * r, cx - k * r, cy - r, cx, cy - r)
    p.close()
    return p


def union(*ps):
    out = ps[0]
    for q in ps[1:]:
        out = op(out, q, U)
    return out


def o_counter(x0):
    return stadium(x0 + S + 0.7, YT + T, x0 + O_W - S - 0.7, YB - T, O_FI)


def letter_o(x0):
    return op(stadium(x0, YT, x0 + O_W, YB, O_F), o_counter(x0), D)


def letter_g(x0):
    xr = x0 + G_W
    xs = xr - S
    rx_o = (O_W - O_F) / 2
    tip_x, tip_y = xs, YB - T  # 끊김 꼭짓점: 스템 안쪽 선 x 보울 안쪽 바닥

    bowl = op(left_round_rect(x0, YT, xr, YB, rx_o),
              left_round_rect(x0 + S + 0.7, YT + T, xs, YB - T, rx_o - S - 0.45), D)
    up_line = lambda x: tip_y - (x - tip_x)  # 꼭짓점을 지나는 45도(우상향)
    upper = op(bowl, poly([(x0 - 50, -50), (xr + 50, -50), (xr + 50, up_line(xr + 50)),
                           (x0 - 50, up_line(x0 - 50))]), I)

    yb2 = YB + G_DESC
    xl = x0 + G_TAIL_INSET
    tail = op(bottom_round_rect(xl, YT, xr, yb2, G_TAIL_RH, G_TAIL_RV),
              bottom_round_rect(xl + S, YT - 10, xs, yb2 - T, G_TAIL_RH - S, G_TAIL_RV - T), D)
    dn_line = lambda x: tip_y + (x - tip_x)  # 꼭짓점을 지나는 45도(우하향)
    term_y = yb2 - G_TAIL_RV + 4  # 꼬리 끝은 수평으로 자른다(Pleos e·s 끝과 같이)
    xmid = xl + G_TAIL_RH
    lower = op(tail, poly([(tip_x, tip_y), (xr + 50, dn_line(xr + 50)), (xr + 50, 500),
                           (x0 - 50, 500), (x0 - 50, term_y), (xmid, term_y), (xmid, tip_y)]), I)
    return union(upper, lower), xr


def build():
    g, g_right = letter_g(0.0)
    ox = g_right + GAP_GO
    o = letter_o(ox)
    bridge = op(rect(g_right - 2, YT, ox + O_W / 2, YT + T), o_counter(ox), D)
    ix = ox + O_W + GAP_OI
    i_stem = rect(ix, YT, ix + S, YB)
    dot = circle(ix + S / 2, DOT_TOP + DOT_D / 2, DOT_D / 2)
    return union(g, bridge, o, i_stem), dot


def to_d(p, dx, dy):
    f = lambda v: f"{round(v, 2):.2f}".rstrip("0").rstrip(".")
    verbs = {"moveTo": "M", "lineTo": "L", "curveTo": "C", "qCurveTo": "Q"}
    out = []
    for verb, pts in p.segments:
        if verb == "closePath":
            out.append("Z")
        elif verb in verbs:
            out.append(verbs[verb] + " ".join(f"{f(x + dx)} {f(y + dy)}" for x, y in pts))
    return "".join(out)


def svg(ink_color=INK, accent=ACCENT, ink_css="currentColor"):
    ink, dot = build()
    bs = [ink.bounds, dot.bounds]
    x0, y0 = min(b[0] for b in bs), min(b[1] for b in bs)
    x1, y1 = max(b[2] for b in bs), max(b[3] for b in bs)
    w, h = round(x1 - x0, 2), round(y1 - y0, 2)
    return f"""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {w:g} {h:g}" width="{w:g}" height="{h:g}" role="img" aria-label="goi">
  <title>goi</title>
  <style>.goi-ink{{fill:var(--goi-ink,{ink_css})}}.goi-accent{{fill:var(--goi-accent,{accent})}}</style>
  <path id="goi-ink" class="goi-ink" fill="{ink_color}" d="{to_d(ink, -x0, -y0)}"/>
  <path id="goi-accent" class="goi-accent" fill="{accent}" d="{to_d(dot, -x0, -y0)}"/>
</svg>
"""


if __name__ == "__main__":
    for name, text in [
        ("goi-logo.svg", svg()),
        ("goi-logo-dark.svg", svg(INK_DARK, ACCENT_DARK, INK_DARK)),
    ]:
        out = FsPath(__file__).with_name(name)
        out.write_text(text, encoding="utf-8")
        print(out)
