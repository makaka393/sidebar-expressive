import { Stack } from "expo-router";
import * as SplashScreen from "expo-splash-screen";
import { StatusBar } from "expo-status-bar";
import * as SystemUI from "expo-system-ui";
import { useCallback, useEffect, useMemo, useState } from "react";
import { AppState, useColorScheme } from "react-native";
import { GestureHandlerRootView } from "react-native-gesture-handler";
import Sidebar from "../modules/sidebar";
import { ThemeChoice, ThemeContext } from "../src/ThemeContext";
import { ColorSource, DEFAULT_SEED, PaletteStyle, SchemePair, schemeFromSeed } from "../src/theme";

SplashScreen.preventAutoHideAsync();

export default function RootLayout() {
  const systemScheme = useColorScheme();
  const [themeChoice, setThemeChoice] = useState<ThemeChoice>('system');
  const [colorSource, setColorSource] = useState<ColorSource>('system');
  const [seedColor, setSeedColor] = useState(DEFAULT_SEED);
  const [paletteStyle, setPaletteStyle] = useState<PaletteStyle>('tonal');
  const [systemSchemes, setSystemSchemes] = useState<SchemePair | null>(null);

  // Wallpaper colors can change while the app is in the background.
  const refreshSystemColors = useCallback(() => {
    Sidebar.getSystemColors().then(setSystemSchemes).catch(() => setSystemSchemes(null));
  }, []);

  useEffect(() => {
    refreshSystemColors();
    const sub = AppState.addEventListener("change", s => { if (s === "active") refreshSystemColors(); });
    return () => sub.remove();
  }, [refreshSystemColors]);

  const appTheme = themeChoice === 'system' ? (systemScheme ?? 'dark') : themeChoice;
  const dark = appTheme === "dark";
  const customSchemes = useMemo(() => schemeFromSeed(seedColor, paletteStyle), [seedColor, paletteStyle]);
  const pair = colorSource === 'system' && systemSchemes ? systemSchemes : customSchemes;
  const colors = dark ? pair.dark : pair.light;

  useEffect(() => { SystemUI.setBackgroundColorAsync(colors.surfaceContainer); }, [colors.surfaceContainer]);

  return (
    <ThemeContext.Provider value={{
      appTheme, themeChoice, setThemeChoice,
      colorSource, setColorSource,
      seedColor, setSeedColor,
      paletteStyle, setPaletteStyle,
      systemSchemes, customSchemes, colors,
    }}>
      <GestureHandlerRootView style={{ flex: 1, backgroundColor: colors.surfaceContainer }}>
        <StatusBar style={dark ? "light" : "dark"} />
        <Stack
          screenOptions={{
            headerShown: false,
            contentStyle: { backgroundColor: colors.surfaceContainer },
            animation: "fade",
          }}
        />
      </GestureHandlerRootView>
    </ThemeContext.Provider>
  );
}
