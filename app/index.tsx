import { Ionicons } from "@expo/vector-icons";
import * as SplashScreen from "expo-splash-screen";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { Alert, AppState, AppStateStatus, Text, View } from "react-native";
import { Gesture, GestureDetector, ScrollView } from "react-native-gesture-handler";
import Animated, {
  EntryExitAnimationFunction,
  FadeIn,
  FadeOut,
  interpolateColor,
  LayoutAnimationConfig,
  runOnJS,
  useAnimatedStyle,
  useSharedValue,
  withSpring,
  withTiming,
} from "react-native-reanimated";
import { useSafeAreaInsets } from "react-native-safe-area-context";
import Sidebar, { InstalledApp, OverlaySettings, PillSettings } from "../modules/sidebar";
import { Button, MorphPressable } from "../src/components/Expressive";
import { M3Switch } from "../src/components/M3Switch";
import { NavBar, NavItem } from "../src/components/NavBar";
import { emphasizedDecelerate, Springs } from "../src/motion";
import { AppsTab, FavItem } from "../src/tabs/AppsTab";
import { BehaviorTab } from "../src/tabs/BehaviorTab";
import { ControlTab } from "../src/tabs/ControlTab";
import { LookTab } from "../src/tabs/LookTab";
import { DEFAULT_SEED, type } from "../src/theme";
import { useAppTheme } from "../src/ThemeContext";

type Tab = "handle" | "behavior" | "control" | "apps";
const TAB_ORDER: Tab[] = ["handle", "behavior", "control", "apps"];

const TABS: NavItem<Tab>[] = [
  { id: "handle", label: "Look", icon: "color-palette-outline", activeIcon: "color-palette" },
  { id: "behavior", label: "Behavior", icon: "settings-outline", activeIcon: "settings" },
  { id: "control", label: "Controls", icon: "grid-outline", activeIcon: "grid" },
  { id: "apps", label: "Apps", icon: "apps-outline", activeIcon: "apps" },
];

const DEFAULT_PILL: PillSettings = {
  height: 80, width: 10, position: 0.5, side: "right", opacity: 0.5, theme: "dark", themeChoice: "system",
  colorSource: "system", seedColor: DEFAULT_SEED, paletteStyle: "tonal",
};

const DEFAULT_OVERLAY: OverlaySettings = {
  autoHideFullscreen: false, showLabels: true, vibration: true, sensitivity: 16,
  quickControlsEnabled: true, showTorch: true, showAutoRotate: true, showAutoBrightness: true, showRingerMode: true,
  showAllApps: true, showEdit: true,
  gesturesEnabled: true, gestureSwipeIn: 'panel',
  gestureSwipeUp: 'none', gestureSwipeDown: 'notifications', gestureDoubleTap: 'none',
  showBrightnessSlider: true, showQuickShare: true,
  showPower: true, showQr: true, showDnd: true,
  showRecentApps: false,
  showKeyboardOnAllApps: false,
  showVolume: true,
  controlTilesOrder: ['torch', 'rotate', 'brightness', 'ringer', 'share', 'power', 'qr', 'dnd'],
  freeformWindows: false,
  blurBehind: false,
  blurRadius: 20,
};

/** Shared-axis X: the incoming tab drifts in a little from the direction of travel and fades up. */
const tabEnter = (dir: number): EntryExitAnimationFunction => () => {
  "worklet";
  return {
    initialValues: { opacity: 0, transform: [{ translateX: dir * 24 }] },
    animations: {
      opacity: withTiming(1, { duration: 200 }),
      transform: [{ translateX: withTiming(0, { duration: 320, easing: emphasizedDecelerate }) }],
    },
  };
};

export default function Index() {
  const {
    appTheme, colors, themeChoice, setThemeChoice, colorSource, setColorSource,
    seedColor, setSeedColor, paletteStyle, setPaletteStyle, customSchemes,
  } = useAppTheme();
  const insets = useSafeAreaInsets();

  const [activeTab, setActiveTab] = useState<Tab>("handle");
  const activeTabRef = useRef<Tab>("handle");
  const dirRef = useRef(1);

  const changeTab = useCallback((tab: Tab) => {
    const from = TAB_ORDER.indexOf(activeTabRef.current);
    const to = TAB_ORDER.indexOf(tab);
    if (from === to) return;
    dirRef.current = to > from ? 1 : -1;
    activeTabRef.current = tab;
    setActiveTab(tab);
  }, []);

  const stepTab = useCallback((delta: number) => {
    const idx = TAB_ORDER.indexOf(activeTabRef.current) + delta;
    if (idx >= 0 && idx < TAB_ORDER.length) changeTab(TAB_ORDER[idx]);
  }, [changeTab]);

  const swipe = useMemo(() => Gesture.Pan()
    .activeOffsetX([-30, 30])
    .failOffsetY([-15, 15])
    .onEnd(e => {
      if (e.translationX < -60 || e.velocityX < -700) runOnJS(stepTab)(1);
      else if (e.translationX > 60 || e.velocityX > 700) runOnJS(stepTab)(-1);
    }), [stepTab]);

  const [loaded, setLoaded] = useState(false);
  const [permissionGranted, setPermissionGranted] = useState<boolean | null>(null);
  const [serviceEnabled, setServiceEnabled] = useState(false);
  const [dndPerm, setDndPerm] = useState(true);
  const [writePerm, setWritePerm] = useState(true);
  const [usagePerm, setUsagePerm] = useState(true);
  const [blurSupported, setBlurSupported] = useState(false);

  const [pill, setPill] = useState<PillSettings>(DEFAULT_PILL);
  const [overlay, setOverlay] = useState<OverlaySettings>(DEFAULT_OVERLAY);

  const [allApps, setAllApps] = useState<InstalledApp[]>([]);
  const [favorites, setFavorites] = useState<FavItem[]>([]);
  const [savedFavKeys, setSavedFavKeys] = useState<string[]>([]);
  const [query, setQuery] = useState("");
  const [appsLoading, setAppsLoading] = useState(false);
  const [appsSaving, setAppsSaving] = useState(false);
  const appsLoadedRef = useRef(false);

  const appState = useRef<AppStateStatus>(AppState.currentState);

  // Initial data fetch — runs once on mount.
  useEffect(() => {
    Promise.all([
      Sidebar.hasOverlayPermission(),
      Sidebar.isServiceEnabled(),
      Sidebar.getPillSettings(),
      Sidebar.getOverlaySettings(),
      Sidebar.hasDndPermission(),
      Sidebar.hasWriteSettingsPermission(),
      Sidebar.hasUsageAccessPermission(),
      Sidebar.getFavorites(),
      Sidebar.isBlurSupported(),
      Sidebar.getLaunchTab(),
    ]).then(([perm, svc, pillSettings, overlaySettings, dnd, write, usage, favs, blur, launchTab]) => {
      setPermissionGranted(perm);
      setServiceEnabled(svc);
      setPill({ ...DEFAULT_PILL, ...pillSettings });
      setThemeChoice(pillSettings.themeChoice ?? pillSettings.theme);
      setColorSource(pillSettings.colorSource ?? "system");
      setSeedColor(pillSettings.seedColor || DEFAULT_SEED);
      setPaletteStyle(pillSettings.paletteStyle ?? "tonal");
      setOverlay({ ...DEFAULT_OVERLAY, ...overlaySettings });
      setDndPerm(dnd);
      setWritePerm(write);
      setUsagePerm(usage);
      setBlurSupported(blur);
      setSavedFavKeys(favs);
      setFavorites(favs.map(pkg => ({ key: pkg, name: pkg.split(".").pop() ?? pkg, icon: null })));
      if (launchTab && TAB_ORDER.includes(launchTab as Tab)) {
        activeTabRef.current = launchTab as Tab;
        setActiveTab(launchTab as Tab);
      }
      setLoaded(true);
    }).catch(() => setLoaded(true)).finally(() => SplashScreen.hideAsync());
  }, [setThemeChoice, setColorSource, setSeedColor, setPaletteStyle]);

  useEffect(() => {
    const sub = AppState.addEventListener("change", (next) => {
      if (appState.current !== "active" && next === "active") {
        Promise.all([
          Sidebar.hasOverlayPermission(),
          Sidebar.hasDndPermission(),
          Sidebar.hasWriteSettingsPermission(),
          Sidebar.hasUsageAccessPermission(),
          Sidebar.isBlurSupported(),
          Sidebar.getLaunchTab(),
        ]).then(([perm, dnd, write, usage, blur, launchTab]) => {
          setPermissionGranted(perm);
          setDndPerm(dnd);
          setWritePerm(write);
          setUsagePerm(usage);
          setBlurSupported(blur);
          if (launchTab) changeTab(launchTab as Tab);
        });
      }
      appState.current = next;
    });
    return () => sub.remove();
  }, [changeTab]);

  // Installed apps are loaded the first time the Apps tab opens.
  useEffect(() => {
    if (activeTab !== "apps" || appsLoadedRef.current) return;
    appsLoadedRef.current = true;
    setAppsLoading(true);
    Sidebar.getInstalledApps()
      .then(apps => {
        setAllApps(apps);
        const map = new Map(apps.map(a => [a.packageName, a]));
        setFavorites(prev => prev.map(f => {
          const app = map.get(f.key);
          return app ? { key: f.key, name: app.name, icon: app.icon } : f;
        }));
      })
      .catch(() => Alert.alert("Error", "Failed to load installed apps."))
      .finally(() => setAppsLoading(false));
  }, [activeTab]);

  async function toggleService(value: boolean) {
    setServiceEnabled(value);
    try {
      if (value) await Sidebar.startService();
      else await Sidebar.stopService();
    } catch {
      setServiceEnabled(!value);
      Alert.alert("Error", `Failed to ${value ? "start" : "stop"} the sidebar service.`);
    }
  }

  useEffect(() => {
    if (!loaded) return;
    const t = setTimeout(() => Sidebar.savePillSettings({
      ...pill,
      theme: appTheme,
      themeChoice,
      colorSource,
      seedColor,
      paletteStyle,
      customScheme: JSON.stringify(customSchemes),
    }), 500);
    return () => clearTimeout(t);
  }, [loaded, pill, themeChoice, appTheme, colorSource, seedColor, paletteStyle, customSchemes]);

  useEffect(() => {
    if (!loaded) return;
    const t = setTimeout(() => Sidebar.saveOverlaySettings(overlay), 500);
    return () => clearTimeout(t);
  }, [loaded, overlay]);

  async function saveFavs() {
    setAppsSaving(true);
    const keys = favorites.map(i => i.key);
    try {
      await Sidebar.saveFavorites(keys);
      setSavedFavKeys(keys);
    } catch {
      Alert.alert("Error", "Failed to save favorites.");
    } finally {
      setAppsSaving(false);
    }
  }

  const favDirty = favorites.length !== savedFavKeys.length || favorites.some((f, i) => f.key !== savedFavKeys[i]);

  const tabBody = () => {
    switch (activeTab) {
      case "handle":
        return <LookTab pill={pill} setPill={setPill} overlay={overlay} setOverlay={setOverlay} blurSupported={blurSupported} />;
      case "behavior":
        return <BehaviorTab overlay={overlay} setOverlay={setOverlay} usagePerm={usagePerm} />;
      case "control":
        return <ControlTab overlay={overlay} setOverlay={setOverlay}
          perms={{ overlay: permissionGranted, dnd: dndPerm, write: writePerm, usage: usagePerm }} />;
      default:
        return null;
    }
  };

  // Nothing is drawn until saved settings arrive (the splash stays up), so nothing
  // animates from defaults to the real values on launch.
  if (!loaded) return <View style={{ flex: 1, backgroundColor: colors.surfaceContainer }} />;

  return (
    <View style={{ flex: 1, backgroundColor: colors.surfaceContainer, paddingTop: insets.top }}>
      <ServiceCard
        enabled={serviceEnabled}
        disabled={!permissionGranted}
        side={pill.side}
        onToggle={toggleService}
      />

      {permissionGranted === false && (
        <Animated.View entering={FadeIn} exiting={FadeOut} style={{
          backgroundColor: colors.errorContainer, borderRadius: 24, padding: 16, gap: 8,
          marginHorizontal: 16, marginBottom: 12,
        }}>
          <View style={{ flexDirection: "row", alignItems: "center", gap: 10 }}>
            <Ionicons name="warning-outline" size={20} color={colors.onErrorContainer} />
            <Text style={[type.titleSmall, { color: colors.onErrorContainer }]}>Overlay permission required</Text>
          </View>
          <Text style={[type.bodyMedium, { color: colors.onErrorContainer }]}>
            Allow {'"'}Display over other apps{'"'} so the sidebar handle can appear on screen.
          </Text>
          <Button label="Grant permission" onPress={Sidebar.requestOverlayPermission} style={{ alignSelf: "flex-start", marginTop: 4 }} />
        </Animated.View>
      )}

      <GestureDetector gesture={swipe}>
        <View style={{ flex: 1 }}>
          <Animated.View key={activeTab} entering={tabEnter(dirRef.current)} style={{ flex: 1 }}>
            <LayoutAnimationConfig skipEntering>
              {activeTab === "apps" ? (
                <AppsTab
                  allApps={allApps}
                  loading={appsLoading}
                  favorites={favorites}
                  setFavorites={setFavorites}
                  query={query}
                  setQuery={setQuery}
                  dirty={favDirty}
                  saving={appsSaving}
                  onSave={saveFavs}
                />
              ) : (
                <ScrollView contentContainerStyle={{ paddingHorizontal: 16, paddingTop: 4, paddingBottom: 24 }}>
                  {tabBody()}
                </ScrollView>
              )}
            </LayoutAnimationConfig>
          </Animated.View>
        </View>
      </GestureDetector>

      <NavBar items={TABS} active={activeTab} onChange={changeTab} />
    </View>
  );
}

/** Hero card for the overlay service: the whole card is the toggle and morphs color/shape with state. */
function ServiceCard({ enabled, disabled, side, onToggle }: {
  enabled: boolean; disabled: boolean; side: PillSettings["side"]; onToggle: (v: boolean) => void;
}) {
  const { colors } = useAppTheme();
  const p = useSharedValue(enabled ? 1 : 0);
  useEffect(() => { p.value = withSpring(enabled ? 1 : 0, Springs.spatial); }, [enabled, p]);

  const card = useAnimatedStyle(() => ({
    backgroundColor: interpolateColor(p.value, [0, 1], [colors.surfaceBright, colors.primaryContainer]),
  }));
  const badge = useAnimatedStyle(() => ({
    borderRadius: 16 + 10 * p.value,
    backgroundColor: interpolateColor(p.value, [0, 1], [colors.surfaceContainerHighest, colors.primary]),
  }));

  const fg = enabled ? colors.onPrimaryContainer : colors.onSurface;
  const sideText = side === "both" ? "both edges" : `the ${side} edge`;

  return (
    <MorphPressable
      radius={28}
      pressedRadius={20}
      scale={0.98}
      disabled={disabled}
      onPress={() => onToggle(!enabled)}
      ripple={false}
      style={{ marginHorizontal: 16, marginTop: 12, marginBottom: 12 }}
    >
      <Animated.View style={[{
        flexDirection: "row", alignItems: "center", gap: 16, paddingHorizontal: 16, paddingVertical: 16,
        opacity: disabled ? 0.6 : 1,
      }, card]}>
        <Animated.View style={[{ width: 52, height: 52, alignItems: "center", justifyContent: "center" }, badge]}>
          <Ionicons name="albums" size={24} color={enabled ? colors.onPrimary : colors.onSurfaceVariant} />
        </Animated.View>
        <View style={{ flex: 1 }}>
          <Text style={[type.titleLarge, { color: fg }]}>Sidebar</Text>
          <Text style={[type.bodyMedium, { color: enabled ? colors.onPrimaryContainer : colors.onSurfaceVariant }]}>
            {enabled ? `Active on ${sideText}` : "Off — tap to show the pull-tab"}
          </Text>
        </View>
        <M3Switch value={enabled} onValueChange={onToggle} disabled={disabled} />
      </Animated.View>
    </MorphPressable>
  );
}
