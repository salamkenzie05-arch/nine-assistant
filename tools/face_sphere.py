#!/usr/bin/env python3
"""Bingkai wajah widget Nine: bola 3D + bingkai perantara untuk gerakan mata yang mulus.

Dijalankan dari akar repo:  python3 tools/face_sphere.py

Yang dikerjakan (idempoten, aman dijalankan berulang):
  1. Mengganti piringan datar tiap bingkai (res/drawable*/face_*.xml) dengan bola 3D yang "melayang":
     badan bergradasi (cahaya dari kiri atas), tepi dibuat lembut tanpa garis (tidak ada stroke), bayangan sekitar
     dan bayangan alas berkurva gaussian (tanpa tepi keras), serta kilau.
  2. Menurunkan seluruh isi wajah sedikit (group translateY) agar pas di bola yang baru.
  3. Membuat bingkai perantara dengan easing (smoothstep) untuk melirik kiri/kanan, mendongak, dan kedip,
     serta ekspresi tambahan (kedip sebelah, kaget, penasaran, mata hati, tertawa) dari mata neutral/happy,
     lalu menulis ulang widget/FaceFrames.kt.
"""
import math
import pathlib
import re

ROOT = pathlib.Path(__file__).resolve().parent.parent / "app" / "src" / "main"
RES = ROOT / "res"
FRAMES_KT = ROOT / "java" / "id" / "nine" / "assistant" / "widget" / "FaceFrames.kt"

MARK = "<!-- sphere3d v3 -->"
MARK_PREFIX = "<!-- sphere3d"  # semua versi lama juga dikenali
HEAD_END = 'android:viewportWidth="108" android:viewportHeight="108">\n'
# v3: bola digeser +4 (di dalam group SPHERE_SHIFT) supaya terpusat, jadi isi wajah tidak perlu digeser lagi (translateY 0).
# v1/v2 menggeser isi wajah -4; keduanya dikenali saat membaca berkas lama.
GROUP_OPEN = '    <group android:translateY="0">\n'
GROUP_OPEN_OLD = '    <group android:translateY="-4">\n'
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

# Ekspresi tambahan yang disusun dari mata "neutral" dan "happy" (tanpa gambar baru), supaya widget lebih hidup:
# kedip sebelah, kaget, penasaran (miring), mata hati yang berdenyut, dan tertawa (naik-turun).
COMPOSED = ["wink", "surprised", "curious", "love1", "love2", "laugh1", "laugh2"]

ALL_NAMES = BASE_NAMES + list(EXTRA) + COMPOSED

HEART = "#FFFF4D6D"


def eyes(content: str):
    """Pecah isi wajah menjadi (blok mata kiri, blok mata kanan). Versi gelap punya dua lingkaran pendar di depan."""
    parts = [p for p in re.split(r"(?m)^(?=    <path )", content) if p.strip()]
    if len(parts) == 4:
        return parts[:2], parts[2:]
    assert len(parts) == 6, f"isi wajah harus 4 atau 6 blok, dapat {len(parts)}"
    return [parts[0], parts[2], parts[3]], [parts[1], parts[4], parts[5]]


def grp(attrs: str, inner: str) -> str:
    return f'    <group android:{attrs.replace(" ", " android:")}>\n{inner}    </group>\n'


def heart(cx: float, cy: float, s: float) -> str:
    """Hati selebar ±1,05·s dan lebih tinggi daripada lebar sedikit (ky), terpusat di mata."""
    ky = 1.35
    y = lambda o: fmt(cy + ky * s * o)
    d = (
        f"M{fmt(cx)},{y(0.62)}"
        f"C{fmt(cx - 0.35 * s)},{y(0.30)} {fmt(cx - 1.05 * s)},{y(-0.05)} {fmt(cx - 1.05 * s)},{y(-0.36)}"
        f"C{fmt(cx - 1.05 * s)},{y(-0.74)} {fmt(cx - 0.45 * s)},{y(-0.84)} {fmt(cx)},{y(-0.40)}"
        f"C{fmt(cx + 0.45 * s)},{y(-0.84)} {fmt(cx + 1.05 * s)},{y(-0.74)} {fmt(cx + 1.05 * s)},{y(-0.36)}"
        f"C{fmt(cx + 1.05 * s)},{y(-0.05)} {fmt(cx + 0.35 * s)},{y(0.30)} {fmt(cx)},{y(0.62)}Z"
    )
    return f'    <path android:pathData="{d}" android:fillColor="{HEART}" />\n'


def composed(neutral: str, happy: str):
    """Nama -> (isi wajah, atribut group luar atau '') untuk ekspresi tambahan."""
    (nl, nr), (hl, hr) = eyes(neutral), eyes(happy)
    eye_l, eye_r = 36.98, 71.02
    cy = 52.16
    out = {
        "wink": ("".join(nl + hr), ""),
        "surprised": (neutral, 'pivotX="54" pivotY="52.16" scaleX="1.08" scaleY="1.28"'),
        "curious": (
            grp(f'pivotX="{eye_l}" pivotY="{cy}" scaleY="0.9"', "".join(nl)) + grp(f'pivotX="{eye_r}" pivotY="{cy}" scaleY="1.15"', "".join(nr)),
            'pivotX="54" pivotY="50" rotation="7"',
        ),
        "love1": (heart(eye_l, cy, 10.5) + heart(eye_r, cy, 10.5), ""),
        "love2": (heart(eye_l, cy, 11.8) + heart(eye_r, cy, 11.8), ""),
        "laugh1": (happy, 'translateY="-1.6"'),
        "laugh2": (happy, 'translateY="0.8"'),
    }
    assert list(out) == COMPOSED
    return out


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


def _alpha_hex(a: float, rgb: str = "000000") -> str:
    return "#" + f"{max(0, min(255, round(a * 255))):02X}" + rgb


def gauss_stops(peak: float, k: float, t_from: float = 0.0, steps=(0.0, 0.25, 0.4, 0.55, 0.68, 0.8, 0.9, 1.0), rgb: str = "000000"):
    """Tahapan gradien berkurva gaussian: alfa = peak di t=0 dan tepat 0 di t=1 (tanpa tepi keras)."""
    end = math.exp(-k)
    out = []
    for t in steps:
        a = peak * (math.exp(-k * t * t) - end) / (1 - end)
        out.append((fmt(t_from + t * (1 - t_from)), _alpha_hex(a, rgb)))
    return out


def sphere(theme: str) -> str:
    light = theme == "light"
    out = [f"    {MARK}\n", '    <group android:translateY="4">\n']
    # Tanpa bayangan di bawah orb (dulu: gumpalan dan bayangan alas yang membuat orb tampak "terbang").
    # pendar tepi luar: hanya kegelapan tipis yang memudar, sepusat dengan bola, supaya siluetnya tidak terlihat bergaris
    out.append(f'    <path android:pathData="{_circle(54, 50, 48)}">\n')
    halo = [("0", "#00000000"), ("0.86", "#00000000")]
    for t, c in gauss_stops(0.11 if light else 0.30, 3.2, t_from=43 / 48):
        halo.append((t, c))
    out.append(_grad(54, 50, 48, halo))
    out.append("    </path>\n")
    # badan bola: cahaya dari kiri atas
    body = (
        [(0, "#FFFFFF"), (0.28, "#F8FAFD"), (0.6, "#E3E8F1"), (0.85, "#C9D1DF"), (1, "#B4BECF")]
        if light
        else [(0, "#3B414F"), (0.25, "#232731"), (0.55, "#12151B"), (0.85, "#0A0C10"), (1, "#050607")]
    )
    out.append(f'    <path android:pathData="{_circle(54, 50, 43)}">\n')
    out.append(_grad(40, 32, 70, body))
    out.append("    </path>\n")
    # sisi gelap lembut di tepi (ambient occlusion), naik perlahan tanpa garis
    rim_peak = 0.13 if light else 0.26
    rim = [("0", "#00000000")]
    for t in (0.70, 0.78, 0.86, 0.93, 1.0):
        rim.append((fmt(t), _alpha_hex(rim_peak * ((t - 0.70) / 0.30) ** 2.2, "0B1220" if light else "000000")))
    out.append(f'    <path android:pathData="{_circle(54, 50, 43)}">\n')
    out.append(_grad(54, 50, 43, rim))
    out.append("    </path>\n")
    # kilau (specular) kiri atas
    out.append('    <group android:pivotX="37" android:pivotY="26" android:rotation="-30" android:scaleY="0.55">\n')
    out.append(f'        <path android:pathData="{_circle(37, 26, 15)}">\n')
    hi = [(0, "#F2FFFFFF"), (0.5, "#99FFFFFF"), (1, "#00FFFFFF")] if light else [(0, "#66FFFFFF"), (0.5, "#33FFFFFF"), (1, "#00FFFFFF")]
    out.append(_grad(37, 26, 15, hi, ind=12))
    out.append("        </path>\n    </group>\n")
    out.append("    </group>\n")
    return "".join(out)


def split(text: str):
    """Pisahkan berkas bingkai menjadi (kepala, isi wajah) baik versi lama maupun yang sudah berbentuk bola."""
    i = text.index(HEAD_END) + len(HEAD_END)
    head, body = text[:i], text[i:]
    head = re.sub(r"<!--.*?-->", "<!-- Bingkai wajah Nine untuk widget (tools/face_sphere.py). -->", head, count=1, flags=re.S)
    if MARK_PREFIX in body:
        opener = GROUP_OPEN if GROUP_OPEN in body else GROUP_OPEN_OLD
        a = body.index(opener) + len(opener)
        b = body.rindex(GROUP_CLOSE)
        content = body[a:b]
        if opener == GROUP_OPEN_OLD:
            # isi wajah v1/v2 ada di dalam group translateY -4; v3 menaruhnya di group translateY 0 setelah bola digeser +4.
            # Posisi relatif wajah terhadap bola tetap sama, jadi isinya tidak perlu diubah.
            pass
        return head, content
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
        _, happy = split((d / f"face_{prefix}_happy.xml").read_text())
        for name, (content, attrs) in composed(neutral, happy).items():
            (d / f"face_{prefix}_{name}.xml").write_text(build(neutral_head, theme, content, attrs))
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
