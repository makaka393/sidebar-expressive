import {
  DynamicScheme,
  Hct,
  SchemeExpressive,
  SchemeFidelity,
  SchemeTonalSpot,
  SchemeVibrant,
  argbFromHex,
  hexFromArgb,
} from "@material/material-color-utilities";
import { Platform, TextStyle } from "react-native";

/** Every M3 color role exchanged with native. Keep in sync with `SidebarColors.ROLE_NAMES`. */
export const ROLE_NAMES = [
  "primary", "onPrimary", "primaryContainer", "onPrimaryContainer",
  "secondary", "onSecondary", "secondaryContainer", "onSecondaryContainer",
  "tertiary", "onTertiary", "tertiaryContainer", "onTertiaryContainer",
  "error", "onError", "errorContainer", "onErrorContainer",
  "surface", "surfaceDim", "surfaceBright",
  "surfaceContainerLowest", "surfaceContainerLow", "surfaceContainer",
  "surfaceContainerHigh", "surfaceContainerHighest",
  "onSurface", "onSurfaceVariant", "outline", "outlineVariant",
  "inverseSurface", "inverseOnSurface", "inversePrimary",
] as const;

export type Role = (typeof ROLE_NAMES)[number];
export type ColorScheme = Record<Role, string>;
export type SchemePair = { light: ColorScheme; dark: ColorScheme };

export type PaletteStyle = "tonal" | "vibrant" | "expressive" | "fidelity";
export type ColorSource = "system" | "custom";

export const DEFAULT_SEED = "#6750A4";

export const SEED_PRESETS = [
  "#6750A4", "#0B57D0", "#00677F", "#386A20",
  "#7C5800", "#A63B00", "#B3261E", "#984061",
];

export const PALETTE_STYLES: { id: PaletteStyle; label: string }[] = [
  { id: "tonal", label: "Tonal" },
  { id: "vibrant", label: "Vibrant" },
  { id: "expressive", label: "Expressive" },
  { id: "fidelity", label: "Fidelity" },
];

function buildScheme(seed: string, style: PaletteStyle, dark: boolean): DynamicScheme {
  const hct = Hct.fromInt(argbFromHex(seed));
  switch (style) {
    case "vibrant": return new SchemeVibrant(hct, dark, 0);
    case "expressive": return new SchemeExpressive(hct, dark, 0);
    case "fidelity": return new SchemeFidelity(hct, dark, 0);
    default: return new SchemeTonalSpot(hct, dark, 0);
  }
}

function toColorScheme(s: DynamicScheme): ColorScheme {
  const out = {} as ColorScheme;
  for (const role of ROLE_NAMES) out[role] = hexFromArgb(s[role]).toUpperCase();
  return out;
}

const schemeCache = new Map<string, SchemePair>();

/** Light + dark schemes generated from a seed color with Material Color Utilities. */
export function schemeFromSeed(seed: string, style: PaletteStyle = "tonal"): SchemePair {
  const valid = isHex(seed) ? seed.toUpperCase() : DEFAULT_SEED;
  const key = `${valid}:${style}`;
  let pair = schemeCache.get(key);
  if (!pair) {
    pair = {
      light: toColorScheme(buildScheme(valid, style, false)),
      dark: toColorScheme(buildScheme(valid, style, true)),
    };
    schemeCache.set(key, pair);
  }
  return pair;
}

export function isHex(v: string) {
  return /^#[0-9A-Fa-f]{6}$/.test(v);
}

/** `#RRGGBB` + opacity → `rgba()` string, used for M3 state layers and scrims. */
export function alpha(hex: string, a: number) {
  const n = parseInt(hex.slice(1), 16);
  return `rgba(${(n >> 16) & 255}, ${(n >> 8) & 255}, ${n & 255}, ${a})`;
}

// ── Type scale (M3 Expressive leans on heavier, emphasized weights) ──────────

const family = Platform.select({ android: "sans-serif", default: undefined });
const familyMedium = Platform.select({ android: "sans-serif-medium", default: undefined });

const t = (size: number, line: number, weight: TextStyle["fontWeight"], track = 0, fam = family): TextStyle =>
  ({ fontSize: size, lineHeight: line, fontWeight: weight, letterSpacing: track, fontFamily: fam });

export const type = {
  displaySmall: t(36, 44, "700", -0.25),
  headlineSmall: t(24, 32, "600"),
  titleLarge: t(22, 28, "600"),
  titleMedium: t(16, 24, "600", 0.15, familyMedium),
  titleSmall: t(14, 20, "600", 0.1, familyMedium),
  bodyLarge: t(16, 24, "400", 0.3),
  bodyMedium: t(14, 20, "400", 0.25),
  bodySmall: t(12, 16, "400", 0.4),
  labelLarge: t(14, 20, "600", 0.1, familyMedium),
  labelMedium: t(12, 16, "600", 0.5, familyMedium),
  labelSmall: t(11, 16, "600", 0.5, familyMedium),
};

// ── Shape scale ──────────────────────────────────────────────────────────────

export const shape = {
  extraSmall: 4,
  small: 8,
  medium: 12,
  large: 16,
  largeIncreased: 20,
  extraLarge: 28,
  extraLargeIncreased: 32,
  full: 999,
};
