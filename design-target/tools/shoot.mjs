import { execFileSync } from 'child_process';

// Renders an HTML file to PNG via headless Chromium at an exact viewport size.
export function shoot(htmlPath, pngPath, width, height) {
  execFileSync('chromium', [
    '--headless=new',
    '--no-sandbox',
    '--disable-gpu',
    '--hide-scrollbars',
    `--screenshot=${pngPath}`,
    `--window-size=${width},${height}`,
    '--force-device-scale-factor=1',
    '--default-background-color=00000000',
    '--run-all-compositor-stages-before-draw',
    '--virtual-time-budget=4000',
    `file://${htmlPath}`,
  ], { stdio: ['ignore', 'ignore', 'pipe'] });
}
