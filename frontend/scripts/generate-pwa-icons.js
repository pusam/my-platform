// SVG -> PNG 아이콘 일괄 생성 (PWA 설치 호환성)
// 실행: node scripts/generate-pwa-icons.js
import sharp from 'sharp';
import { readFileSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const __dirname = dirname(fileURLToPath(import.meta.url));
const PUBLIC = join(__dirname, '..', 'public');

const sources = [
  { in: 'pwa-icon.svg',          stem: 'pwa-icon' },
  { in: 'pwa-icon-maskable.svg', stem: 'pwa-icon-maskable' },
];
const sizes = [192, 512];

for (const { in: src, stem } of sources) {
  const svg = readFileSync(join(PUBLIC, src));
  for (const size of sizes) {
    const out = `${stem}-${size}.png`;
    await sharp(svg, { density: 384 })
      .resize(size, size)
      .png({ compressionLevel: 9 })
      .toFile(join(PUBLIC, out));
    console.log(`✓ ${out}`);
  }
}

// iOS 홈 화면 아이콘 (180x180)
await sharp(readFileSync(join(PUBLIC, 'pwa-icon.svg')), { density: 384 })
  .resize(180, 180)
  .png({ compressionLevel: 9 })
  .toFile(join(PUBLIC, 'apple-touch-icon.png'));
console.log('✓ apple-touch-icon.png');
