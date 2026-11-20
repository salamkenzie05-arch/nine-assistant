#!/usr/bin/env python3
"""Bingkai wajah widget Nine: bola 3D + bingkai perantara untuk gerakan mata yang mulus.

Dijalankan dari akar repo:  python3 tools/face_sphere.py

Yang dikerjakan (idempoten, aman dijalankan berulang):
  1. Mengganti piringan datar tiap bingkai (res/drawable*/face_*.xml) dengan bola 3D:
     bayangan lembut + bayangan alas, badan bergradasi (cahaya dari kiri atas), tepi lebih gelap, dan kilau.
  2. Menurunkan seluruh isi wajah sedikit (group translateY) agar pas di bola yang baru.
  3. Membuat bingkai perantara dengan easing (smoothstep) untuk melirik kiri/kanan, mendongak, dan kedip,
     lalu menulis ulang widget/FaceFrames.kt.
"""
import pathlib
import re

ROOT = pathlib.Path(__file__).resolve().parent.parent / "app" / "src" / "main"
RES = ROOT / "res"
FRAMES_KT = ROOT / "java" / "id" / "nine" / "assistant" / "widget" / "FaceFrames.kt"

MARK = "<!-- sphere3d v1 -->"
HEAD_END = 'android:viewportWidth="108" android:viewportHeight="108">\n'
GROUP_OPEN = '    <group android:translateY="-4">\n'
GROUP_CLOSE = '    </group>\n</vector>\n'

# (folder, awalan nama file, tema warna)
SETS = [
    ("drawable", "light", "light"),
    ("drawable", "dark", "dark"),
    ("drawable", "auto", "light"),
    ("drawable-night", "auto", "dark"),
]

BASE_NAMES = [
    "neutral", "blink", "left1", "left2", "right1", "right2", "up", "happy",
    "sleepy1", "sleepy2", "sleepy3", "sleepyclosed",
    "charge1", "charge2", "chargeblink", "lowbat1", "lowbat2", "lowbatblink",
]


def smooth(t: float) -> float:
    return t * t * (3 - 2 * t)


def fmt(v: float) -> str:
    s = f"{v:.2f}".rstrip("0").rstrip(".")
    return "0" if s in ("-0", "") else s


# Bingkai perantara: nama -> atribut <group>. Eksponen easing membuat gerak melambat di ujung.
EXTRA = {}
for k in range(1, 5):  # melirik: 0 -> left2 / right2 (pergeseran 4,6 dan -0,4)
    s = smooth(k / 5)
    EXTRA[f"gl{k}"] = f'translateX="{fmt(-4.6 * s)}" translateY="{fmt(-0.4 * s)}"'
    EXTRA[f"gr{k}"] = f'translateX="{fmt(4.6 * s)}" translateY="{fmt(-0.4 * s)}"'
for k in range(1, 4):  # mendongak: 0 -> up (naik 3,06)
    EXTRA[f"up{k}"] = f'translateY="{fmt(-3.06 * smooth(k / 4))}"'
EXTRA["bl1"] = 'pivotX="54" pivotY="52.16" scaleY="0.55"'  # kelopak setengah turun
EXTRA["bl2"] = 'pivotX="54" pivotY="52.16" scaleY="0.22"'  # hampir menutup

ALL_NAMES = BASE_NAMES + list(EXTRA)


def _grad(cx, cy, r, stops, ind=8):
    a, b, c = " " * ind, " " * (ind + 4), " " * (ind + 8)
    items = "\n".join(f'{c}<item android:offset="{o}" android:color="{col}" />' for o, col in stops)
    return (
        f'{a}<aapt:attr name="android:fillColor">\n'
        f'{b}<gradient android:type="radial" android:centerX="{cx}" android:centerY="{cy}" android:gradientRadius="{r}">\n'
        f"{items}\n"
        f"{b}</gradient>\n"
        f"{a}</aapt:attr>\n"
    )


def _circle(cx, cy, r):
    return f"M{fmt(cx - r)},{cy}a{r},{r} 0 1,0 {fmt(2 * r)},0a{r},{r} 0 1,0 -{fmt(2 * r)},0Z"


def sphere(theme: str) -> str:
    light = theme == "light"
    out = [f"    {MARK}\n"]
    # bayangan jatuh lembut (sedikit ke bawah) dan bayangan alas
    out.append(f'    <path android:pathData="{_circle(54, 58, 50)}">\n')
    out.append(_grad(54, 58, 50, [(0.82, "#3A000000" if light else "#66000000"), (1, "#00000000")]))
    out.append("    </path>\n")
    out.append('    <group android:pivotX="54" android:pivotY="98" android:scaleY="0.14">\n')
    out.append(f'        <path android:pathData="{_circle(54, 98, 30)}">\n')
    out.append(_grad(54, 98, 30, [(0, "#55000000" if light else "#80000000"), (0.6, "#22000000"), (1, "#00000000")], ind=12))
    out.append("        </path>\n    </group>\n")
    # badan bola: cahaya dari kiri atas
    body = (
        [(0, "#FFFFFF"), (0.28, "#F8FAFD"), (0.6, "#E3E8F1"), (0.85, "#C9D1DF"), (1, "#B4BECF")]
        if light
        else [(0, "#3B414F"), (0.25, "#232731"), (0.55, "#12151B"), (0.85, "#0A0C10"), (1, "#050607")]
    )
    out.append(f'    <path android:pathData="{_circle(54, 50, 43)}">\n')
    out.append(_grad(40, 32, 70, body))
    out.append("    </path>\n")
    # tepi lebih gelap (ambient occlusion)
    out.append(f'    <path android:pathData="{_circle(54, 50, 43)}">\n')
    out.append(_grad(54, 50, 43, [(0.7, "#00000000"), (1, "#240B1220" if light else "#40000000")]))
    out.append("    </path>\n")
    # garis tepi tipis
    stroke = "#1F0B1220" if light else "#73C7861A"
    out.append(
        f'    <path android:pathData="{_circle(54, 50, 43)}" android:fillColor="#00000000" android:strokeColor="{stroke}" '
        'android:strokeWidth="0.9" android:strokeLineCap="round" android:strokeLineJoin="round" />\n'
    )
    # kilau (specular) kiri atas
    out.append('    <group android:pivotX="37" android:pivotY="26" android:rotation="-30" android:scaleY="0.55">\n')
    out.append(f'        <path android:pathData="{_circle(37, 26, 15)}">\n')
    hi = [(0, "#F2FFFFFF"), (0.5, "#99FFFFFF"), (1, "#00FFFFFF")] if light else [(0, "#66FFFFFF"), (0.5, "#33FFFFFF"), (1, "#00FFFFFF")]
    out.append(_grad(37, 26, 15, hi, ind=12))
    out.append("        </path>\n    </group>\n")
    return "".join(out)


def split(text: str):
    """Pisahkan berkas bingkai menjadi (kepala, isi wajah) baik versi lama maupun yang sudah berbentuk bola."""
    i = text.index(HEAD_END) + len(HEAD_END)
    head, body = text[:i], text[i:]
    head = re.sub(r"<!--.*?-->", "<!-- Bingkai wajah Nine untuk widget (tools/face_sphere.py). -->", head, count=1, flags=re.S)
    if MARK in body:
        a = body.index(GROUP_OPEN) + len(GROUP_OPEN)
        b = body.rindex(GROUP_CLOSE)
        return head, body[a:b]
    body = re.sub(r'    <path android:pathData="M2,56a52,52[^>]*>\n.*?\n    </path>\n', "", body, count=1, flags=re.S)
    body, n = re.subn(r'    <path android:pathData="M8,54a46,46[^\n]*/>\n', "", body)
    assert n == 2, f"piringan lama tidak ditemukan ({n})"
    body = body.replace("</vector>\n", "").replace("</vector>", "")
    return head, body if body.endswith("\n") else body + "\n"


def build(head: str, theme: str, content: str, group_attrs: str = "") -> str:
    inner = content
    if group_attrs:
        inner = f"    <group android:{group_attrs.replace(' ', ' android:')}>\n{content}    </group>\n"
    return head + sphere(theme) + GROUP_OPEN + inner + GROUP_CLOSE


def main():
    total = 0
    for folder, prefix, theme in SETS:
        d = RES / folder
        neutral_head, neutral = split((d / f"face_{prefix}_neutral.xml").read_text())
        for name in BASE_NAMES:
            p = d / f"face_{prefix}_{name}.xml"
            head, content = split(p.read_text())
            p.write_text(build(head, theme, content))
            total += 1
        for name, attrs in EXTRA.items():
            (d / f"face_{prefix}_{name}.xml").write_text(build(neutral_head, theme, neutral, attrs))
            total += 1

    # FaceFrames.kt
    lines = [
        "package id.nine.assistant.widget",
        "",
        "import id.nine.assistant.R",
        "",
        "/** Dibuat oleh tools/face_sphere.py: bingkai wajah widget per tema (light, dark, auto = ikut mode gelap sistem). */",
        "object FaceFrames {",
    ]
    for const, prefix in (("LIGHT", "light"), ("DARK", "dark"), ("AUTO", "auto")):
        lines.append(f"    val {const}: Map<String, Int> = mapOf(")
        entries = [f'        "{n}" to R.drawable.face_{prefix}_{n}' for n in ALL_NAMES]
        lines.append(",\n".join(entries))
        lines.append("    )")
        lines.append("")
    lines.append("}")
    FRAMES_KT.write_text("\n".join(lines) + "\n")
    print(f"{total} bingkai ditulis, {len(ALL_NAMES)} nama bingkai per tema")


if __name__ == "__main__":
    main()
