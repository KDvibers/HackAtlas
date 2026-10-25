"""Read-only PNG verification; writes its report without altering image pixels."""
from pathlib import Path
from PIL import Image
import hashlib
import json

root = Path(__file__).resolve().parents[2]
records = []
for source in sorted((root / 'design/assets/illustrations').glob('*.png')):
    app_copy = root / 'mobile/shared/src/commonMain/composeResources/drawable' / source.name
    with Image.open(source) as image:
        assert image.mode == 'RGBA', source
        alpha = image.getchannel('A')
        extrema = alpha.getextrema()
        assert extrema == (0, 255), (source, extrema)
        same_bytes = source.read_bytes() == app_copy.read_bytes()
        assert same_bytes, source
        records.append({'name': source.name, 'dimensions': image.size, 'mode': image.mode,
                        'alphaExtrema': extrema,
                        'transparentPixelCount': alpha.histogram()[0],
                        'sha256': hashlib.sha256(source.read_bytes()).hexdigest(),
                        'appCopyMatches': same_bytes})
target = root / 'design/assets/ILLUSTRATION_AUDIT.json'
target.write_text(json.dumps({'checkedOn': '2026-10-12', 'images': records}, indent=2) + '\n', encoding='utf-8')
print(json.dumps({'images': len(records), 'allRgbaWithTransparency': True, 'allAppCopiesMatch': True}))
