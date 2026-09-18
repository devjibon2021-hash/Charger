import sharp from 'sharp';
import fs from 'fs';
import path from 'path';

const publicDir = path.resolve('public');

// 1. Standard icon SVG (for any purpose, 512x512)
const standardSvg = `
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 512 512" width="512" height="512">
  <defs>
    <linearGradient id="bgGrad" x1="0%" y1="0%" x2="100%" y2="100%">
      <stop offset="0%" stop-color="#09090b" />
      <stop offset="60%" stop-color="#0f172a" />
      <stop offset="100%" stop-color="#064e3b" />
    </linearGradient>
    <linearGradient id="boltGrad" x1="0%" y1="0%" x2="100%" y2="100%">
      <stop offset="0%" stop-color="#6ee7b7" />
      <stop offset="50%" stop-color="#10b981" />
      <stop offset="100%" stop-color="#059669" />
    </linearGradient>
    <linearGradient id="batteryGrad" x1="0%" y1="0%" x2="0%" y2="100%">
      <stop offset="0%" stop-color="#34d399" />
      <stop offset="100%" stop-color="#059669" />
    </linearGradient>
    <filter id="glow" x="-20%" y="-20%" width="140%" height="140%">
      <feGaussianBlur stdDeviation="10" result="blur" />
      <feComposite in="SourceGraphic" in2="blur" operator="over" />
    </filter>
  </defs>
  <!-- Background with squircle corners -->
  <rect width="512" height="512" rx="112" fill="url(#bgGrad)" />

  <!-- Outer subtle circular ring -->
  <circle cx="256" cy="256" r="196" stroke="#10b981" stroke-opacity="0.28" stroke-width="4" fill="none" stroke-dasharray="14 10" />

  <!-- Battery Outer Shell -->
  <rect x="160" y="148" width="192" height="264" rx="34" fill="none" stroke="#f4f4f5" stroke-width="16" stroke-linecap="round" />
  <!-- Battery Top Terminal -->
  <path d="M218 132 C218 122 228 114 240 114 L272 114 C284 114 294 122 294 132 L294 148 L218 148 Z" fill="#f4f4f5" />

  <!-- Charge Liquid Level (Emerald) -->
  <rect x="176" y="244" width="160" height="152" rx="20" fill="url(#batteryGrad)" fill-opacity="0.85" />

  <!-- Lightning Bolt -->
  <path d="M274 176 L198 280 L252 280 L238 354 L314 250 L262 250 Z" fill="url(#boltGrad)" filter="url(#glow)" />
</svg>
`;

// 2. Maskable icon SVG (full-bleed background, central 76% safe-zone to comply with Android & PWABuilder)
const maskableSvg = `
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 512 512" width="512" height="512">
  <defs>
    <linearGradient id="maskableBg" x1="0%" y1="0%" x2="100%" y2="100%">
      <stop offset="0%" stop-color="#09090b" />
      <stop offset="50%" stop-color="#0f172a" />
      <stop offset="100%" stop-color="#064e3b" />
    </linearGradient>
    <linearGradient id="boltGrad" x1="0%" y1="0%" x2="100%" y2="100%">
      <stop offset="0%" stop-color="#6ee7b7" />
      <stop offset="50%" stop-color="#10b981" />
      <stop offset="100%" stop-color="#059669" />
    </linearGradient>
    <linearGradient id="batteryGrad" x1="0%" y1="0%" x2="0%" y2="100%">
      <stop offset="0%" stop-color="#34d399" />
      <stop offset="100%" stop-color="#059669" />
    </linearGradient>
    <filter id="glow" x="-20%" y="-20%" width="140%" height="140%">
      <feGaussianBlur stdDeviation="8" result="blur" />
      <feComposite in="SourceGraphic" in2="blur" operator="over" />
    </filter>
  </defs>
  <!-- Full bleed 100% background (no rounded corners so safe zone is respected) -->
  <rect width="512" height="512" fill="url(#maskableBg)" />

  <!-- Scaled content inside central 76% safe zone (center 256,256, scale: 0.76) -->
  <g transform="translate(61.44, 61.44) scale(0.76)">
    <!-- Outer subtle circular ring -->
    <circle cx="256" cy="256" r="196" stroke="#10b981" stroke-opacity="0.32" stroke-width="5" fill="none" stroke-dasharray="14 10" />

    <!-- Battery Outer Shell -->
    <rect x="160" y="148" width="192" height="264" rx="34" fill="none" stroke="#f4f4f5" stroke-width="16" stroke-linecap="round" />
    <!-- Battery Top Terminal -->
    <path d="M218 132 C218 122 228 114 240 114 L272 114 C284 114 294 122 294 132 L294 148 L218 148 Z" fill="#f4f4f5" />

    <!-- Charge Liquid Level (Emerald) -->
    <rect x="176" y="244" width="160" height="152" rx="20" fill="url(#batteryGrad)" fill-opacity="0.85" />

    <!-- Lightning Bolt -->
    <path d="M274 176 L198 280 L252 280 L238 354 L314 250 L262 250 Z" fill="url(#boltGrad)" filter="url(#glow)" />
  </g>
</svg>
`;

async function generate() {
  if (!fs.existsSync(publicDir)) {
    fs.mkdirSync(publicDir, { recursive: true });
  }

  // Write icon.svg & icon-maskable.svg
  fs.writeFileSync(path.join(publicDir, 'icon.svg'), standardSvg.trim());
  fs.writeFileSync(path.join(publicDir, 'icon-maskable.svg'), maskableSvg.trim());
  console.log('Written SVG files.');

  // 1. pwa-512x512.png
  await sharp(Buffer.from(standardSvg))
    .resize(512, 512)
    .png({ quality: 100 })
    .toFile(path.join(publicDir, 'pwa-512x512.png'));
  console.log('Generated pwa-512x512.png');

  // 2. pwa-192x192.png
  await sharp(Buffer.from(standardSvg))
    .resize(192, 192)
    .png({ quality: 100 })
    .toFile(path.join(publicDir, 'pwa-192x192.png'));
  console.log('Generated pwa-192x192.png');

  // 3. pwa-maskable-512x512.png
  await sharp(Buffer.from(maskableSvg))
    .resize(512, 512)
    .png({ quality: 100 })
    .toFile(path.join(publicDir, 'pwa-maskable-512x512.png'));
  console.log('Generated pwa-maskable-512x512.png');

  // 4. apple-touch-icon.png (180x180)
  await sharp(Buffer.from(standardSvg))
    .resize(180, 180)
    .png({ quality: 100 })
    .toFile(path.join(publicDir, 'apple-touch-icon.png'));
  console.log('Generated apple-touch-icon.png');

  // 5. favicon.png (64x64)
  await sharp(Buffer.from(standardSvg))
    .resize(64, 64)
    .png({ quality: 100 })
    .toFile(path.join(publicDir, 'favicon.png'));
  console.log('Generated favicon.png');

  // 6. favicon.ico (32x32)
  await sharp(Buffer.from(standardSvg))
    .resize(32, 32)
    .png({ quality: 100 })
    .toFile(path.join(publicDir, 'favicon.ico'));
  console.log('Generated favicon.ico');

  console.log('All PWA Builder compliant icons generated successfully!');
}

generate().catch(err => {
  console.error('Error generating icons:', err);
  process.exit(1);
});
