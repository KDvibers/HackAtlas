"""Fetch licensed Google Fonts, create static app faces, verify glyphs, render a specimen.

Run with Python + fonttools + Pillow from the repository root.
Sources are retained verbatim; static faces derive only from variable-font instancing.
"""
from pathlib import Path
import hashlib
import json
import shutil
import urllib.request
import urllib.parse

from fontTools.ttLib import TTFont
from fontTools.varLib.instancer import instantiateVariableFont
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[3]
ASSETS = ROOT / 'design/assets'
SOURCES = ASSETS / 'fonts/sources'
RES = ROOT / 'mobile/shared/src/commonMain/composeResources/font'
LICENSES = ASSETS / 'licenses'
PACKAGED_LICENSES = ROOT / 'mobile/shared/src/commonMain/composeResources/files/licenses'
for directory in (SOURCES, RES, LICENSES, PACKAGED_LICENSES):
    directory.mkdir(parents=True, exist_ok=True)

BASE = 'https://raw.githubusercontent.com/google/fonts/main/ofl'
families = {
    'pressstart2p': 'PressStart2P-Regular.ttf',
    'inter': 'Inter[opsz,wght].ttf',
    'exo2': 'Exo2[wght].ttf',
    'jetbrainsmono': 'JetBrainsMono-Regular.ttf',
}
expected_sha256 = {
    'pressstart2p': '034c77f1f05ec89421e4a63f0e3a4ca1ecf852cc6d2bf611f126f275728e017d',
    'inter': '29160a80ff49ddcab2c97711247e08b1fab27a484a329ce8b813d820dc559031',
    'exo2': '205a448676a2586f9c57c25f3d5c58ca8db7e6cf5edf7506783a010c6fe2bfb5',
    'jetbrainsmono': 'e6fd0d7e91550b3ed2b735d4312474362c4716edc4fc0577a0f61ed782d5aed1',
}
sources = {}
for family, filename in families.items():
    safe_name = Path(filename).name
    url = f'{BASE}/{family}/' + urllib.parse.quote(filename, safe='/')
    if family == 'jetbrainsmono':
        url = 'https://raw.githubusercontent.com/JetBrains/JetBrainsMono/master/fonts/ttf/JetBrainsMono-Regular.ttf'
    dest = SOURCES / safe_name
    if not dest.exists():
        dest.write_bytes(urllib.request.urlopen(url, timeout=30).read())
    digest = hashlib.sha256(dest.read_bytes()).hexdigest()
    assert digest == expected_sha256[family], f'Unexpected font bytes: {family}; review source before accepting.'
    license_url = f'{BASE}/{family}/OFL.txt'
    license_dest = LICENSES / f'{family}-OFL.txt'
    if not license_dest.exists():
        license_dest.write_bytes(urllib.request.urlopen(license_url, timeout=30).read())
    shutil.copyfile(license_dest, PACKAGED_LICENSES / license_dest.name)
    sources[family] = {'url': url, 'file': str(dest.relative_to(ROOT)),
                       'sha256': hashlib.sha256(dest.read_bytes()).hexdigest(),
                       'license': str(license_dest.relative_to(ROOT)), 'licenseUrl': license_url}

def static_face(family, output, axes=None):
    source = SOURCES / Path(families[family]).name
    path = RES / output
    if not axes:
        shutil.copyfile(source, path)
        return path
    font = TTFont(source, recalcTimestamp=False)
    if axes:
        font = instantiateVariableFont(font, axes, inplace=False)
        # Distinguish derived static faces from the originals and honor any RFN.
        new_family = {'inter': 'HackAtlas Body', 'exo2': 'HackAtlas Event'}[family]
        weight = axes['wght']
        style = {400: 'Regular', 600: 'SemiBold', 700: 'Bold'}[weight]
        names = {1: new_family, 2: style, 3: f'{new_family} {style} 2026-10-12',
                 4: f'{new_family} {style}', 6: new_family.replace(' ', '') + '-' + style,
                 16: new_family, 17: style}
        for record in font['name'].names:
            if record.nameID in names:
                record.string = names[record.nameID].encode(record.getEncoding())
    font.recalcTimestamp = False
    font.save(path)
    return path

outputs = [
    static_face('pressstart2p', 'press_start_2p.ttf'),
    static_face('inter', 'inter_regular.ttf', {'wght': 400, 'opsz': 14}),
    static_face('inter', 'inter_semibold.ttf', {'wght': 600, 'opsz': 14}),
    static_face('inter', 'inter_bold.ttf', {'wght': 700, 'opsz': 14}),
    static_face('exo2', 'exo_2_bold.ttf', {'wght': 700}),
    static_face('jetbrainsmono', 'jetbrains_mono_regular.ttf'),
]
sample = 'ХакатоныПрофильСохранённоеЙЁ0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz'
cyrillic = ''.join(chr(code) for code in range(0x410, 0x450)) + 'Ёё'
report = {'checkedOn': '2026-10-12', 'sources': sources, 'fonts': []}
for path in outputs:
    font = TTFont(path)
    cmap = font.getBestCmap()
    missing = [char for char in sorted(set(sample + cyrillic)) if ord(char) not in cmap]
    record = {'file': str(path.relative_to(ROOT)), 'sha256': hashlib.sha256(path.read_bytes()).hexdigest(),
              'missingRequiredGlyphs': missing, 'glyphCount': len(cmap),
              'currencyGlyphs': {char: ord(char) in cmap for char in '₽₸€$'}}
    report['fonts'].append(record)
    assert not missing, (path, missing)
(ASSETS / 'fonts/FONT_AUDIT.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')

# This is a font specimen, not an application screenshot or generated UI.
image = Image.new('RGB', (1170, 1230), '#050507')
draw = ImageDraw.Draw(image)
body = ImageFont.truetype(str(RES / 'inter_regular.ttf'), 27)
mono = ImageFont.truetype(str(RES / 'jetbrains_mono_regular.ttf'), 23)
pixel = ImageFont.truetype(str(RES / 'press_start_2p.ttf'), 66)
event = ImageFont.truetype(str(RES / 'exo_2_bold.ttf'), 57)
draw.text((50, 35), 'HACKATLAS / FONT SPECIMEN / M1 CANDIDATE', font=mono, fill='#63D8FF')
draw.text((50, 84), 'Real font files · no system fallback used in this specimen', font=body, fill='#C0B8CC')
y = 158
for line in ['Хакатоны', 'Профиль', 'Сохранённое', 'Й Ё 0123456789']:
    font = pixel if len(line) < 12 else ImageFont.truetype(str(RES / 'press_start_2p.ttf'), 55)
    draw.text((53, y + 3), line, font=font, fill='#E91DF4')
    draw.text((48, y - 1), line, font=font, fill='#63D8FF')
    draw.text((50, y), line, font=font, fill='#F5F2FA')
    y += 110
draw.text((50, 635), 'Exo 2 / 700 — event names', font=mono, fill='#63D8FF')
draw.text((50, 685), 'AI Sprint 2026 · Финтех Хакатон', font=event, fill='#F5F2FA')
draw.text((50, 805), 'Inter / 400, 600, 700 — readable content', font=mono, fill='#63D8FF')
draw.text((50, 860), 'Находи. Участвуй. Создавай будущее.', font=body, fill='#F5F2FA')
draw.text((50, 909), 'Дедлайн заявки — отдельная дата. Й, Ё, ё. ₽ ₸ € $', font=body, fill='#C0B8CC')
draw.text((50, 1020), 'JETBRAINS MONO / TECH LABELS', font=mono, fill='#63D8FF')
draw.text((50, 1070), 'Код сегодня — возможности завтра. AI / ML', font=mono, fill='#C0B8CC')
draw.text((50, 1150), 'Original reference font unknown. Visual approval pending.', font=body, fill='#9F97AD')
image.save(ASSETS / 'fonts/typography-specimen.png')
print(json.dumps({'checkedFonts': len(report['fonts']), 'missingRequiredGlyphs': 0,
                  'audit': 'design/assets/fonts/FONT_AUDIT.json',
                  'specimen': 'design/assets/fonts/typography-specimen.png'}))
