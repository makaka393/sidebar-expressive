/**
 * Web stand-in for the Android native module so the settings UI can be previewed with
 * `npx expo start --web`. Nothing here touches a device; values live in memory.
 */
import type { InstalledApp, OverlaySettings, PillSettings, PillSettingsPayload } from './sidebar';
import type { SchemePair } from '../src/theme';
import { schemeFromSeed } from '../src/theme';

export type { InstalledApp, OverlaySettings, PillSettings, PillSettingsPayload };

const APP_NAMES = [
  'Calendar', 'Camera', 'Chrome', 'Clock', 'Contacts', 'Drive', 'Files', 'Gmail', 'Keep',
  'Maps', 'Messages', 'Music', 'Phone', 'Photos', 'Settings', 'Telegram', 'YouTube',
];
const HUES = ['#4285F4', '#EA4335', '#FBBC04', '#34A853', '#A142F4', '#24C1E0', '#F538A0'];

function iconPng(color: string) {
  if (typeof document === 'undefined') return '';
  const c = document.createElement('canvas');
  c.width = c.height = 96;
  const g = c.getContext('2d')!;
  g.fillStyle = color;
  g.beginPath();
  g.arc(48, 48, 46, 0, Math.PI * 2);
  g.fill();
  g.fillStyle = 'rgba(255,255,255,0.9)';
  g.beginPath();
  g.arc(48, 48, 18, 0, Math.PI * 2);
  g.fill();
  return c.toDataURL('image/png').split(',')[1];
}

let favorites = ['com.example.telegram', 'com.example.maps', 'com.example.camera'];
let pill: PillSettings = {
  height: 80, width: 10, position: 0.5, side: 'right', opacity: 0.6, theme: 'light', themeChoice: 'system',
  colorSource: 'system', seedColor: '#6750A4', paletteStyle: 'tonal',
};
let overlay: OverlaySettings | null = null;
let serviceEnabled = true;

// Pretend wallpaper: a teal-ish dynamic palette.
const wallpaper: SchemePair = schemeFromSeed('#3A7D7C', 'tonal');

const ok = () => Promise.resolve();

export default {
  getInstalledApps(): Promise<InstalledApp[]> {
    return Promise.resolve(APP_NAMES.map((name, i) => ({
      name, packageName: `com.example.${name.toLowerCase()}`, icon: iconPng(HUES[i % HUES.length]),
    })));
  },
  saveFavorites(packages: string[]) { favorites = packages; return ok(); },
  getFavorites() { return Promise.resolve(favorites); },
  hasOverlayPermission() { return Promise.resolve(true); },
  requestOverlayPermission: ok,
  startService() { serviceEnabled = true; return ok(); },
  stopService() { serviceEnabled = false; return ok(); },
  isServiceEnabled() { return Promise.resolve(serviceEnabled); },
  savePillSettings(settings: PillSettingsPayload) {
    const { customScheme: _ignored, ...rest } = settings;
    pill = rest;
    return ok();
  },
  getPillSettings() { return Promise.resolve(pill); },
  getOverlaySettings() { return Promise.resolve(overlay ?? ({} as OverlaySettings)); },
  saveOverlaySettings(settings: OverlaySettings) { overlay = settings; return ok(); },
  getSystemColors() { return Promise.resolve(wallpaper); },
  getWallpaperColors() { return Promise.resolve(['#3A7D7C', '#C08497', '#6D597A']); },
  isBlurSupported() { return Promise.resolve(true); },
  getLaunchTab() { return Promise.resolve(null); },
  hasDndPermission() { return Promise.resolve(false); },
  requestDndPermission: ok,
  hasWriteSettingsPermission() { return Promise.resolve(true); },
  requestWriteSettingsPermission: ok,
  hasUsageAccessPermission() { return Promise.resolve(false); },
  requestUsageAccessPermission: ok,
  hasShizukuPermission() { return Promise.resolve('not_running' as const); },
  requestShizukuPermission() { return Promise.resolve('not_running' as const); },
};
