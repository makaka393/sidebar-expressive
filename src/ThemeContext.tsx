import { createContext, useContext } from "react";
import { ColorScheme, ColorSource, DEFAULT_SEED, PaletteStyle, SchemePair, schemeFromSeed } from "./theme";

export type AppTheme = 'light' | 'dark';
export type ThemeChoice = 'light' | 'dark' | 'system';

interface ThemeContextValue {
  appTheme: AppTheme;
  themeChoice: ThemeChoice;
  setThemeChoice: (choice: ThemeChoice) => void;
  colorSource: ColorSource;
  setColorSource: (source: ColorSource) => void;
  seedColor: string;
  setSeedColor: (hex: string) => void;
  paletteStyle: PaletteStyle;
  setPaletteStyle: (style: PaletteStyle) => void;
  /** Wallpaper-derived schemes from the device, or null below Android 12. */
  systemSchemes: SchemePair | null;
  /** Schemes generated from `seedColor`; persisted for the overlay when source is custom. */
  customSchemes: SchemePair;
  /** Active scheme for the current source + appearance. */
  colors: ColorScheme;
}

const fallback = schemeFromSeed(DEFAULT_SEED);

export const ThemeContext = createContext<ThemeContextValue>({
  appTheme: 'dark',
  themeChoice: 'system',
  setThemeChoice: () => {},
  colorSource: 'system',
  setColorSource: () => {},
  seedColor: DEFAULT_SEED,
  setSeedColor: () => {},
  paletteStyle: 'tonal',
  setPaletteStyle: () => {},
  systemSchemes: null,
  customSchemes: fallback,
  colors: fallback.dark,
});

export function useAppTheme() {
  return useContext(ThemeContext);
}
