import { ReactNode, useEffect } from "react";
import { Text, View } from "react-native";
import Animated, { useAnimatedStyle, useSharedValue, withSpring } from "react-native-reanimated";
import { PillSettings } from "../../modules/sidebar";
import { Springs } from "../motion";
import { alpha, ColorScheme, type } from "../theme";
import { useAppTheme } from "../ThemeContext";

const PHONE_W = 116;
const PHONE_H = 196;
const SCALE = 0.32;

/** Miniature phones showing the handle and the open panel in the current colors. */
export function OverlayPreview({ pill, blur }: { pill: PillSettings; blur: boolean }) {
  const { colors } = useAppTheme();
  const sides = pill.side === "both" ? (["left", "right"] as const) : [pill.side];
  return (
    <View style={{
      backgroundColor: colors.surfaceBright, borderRadius: 28, paddingVertical: 20,
      flexDirection: "row", justifyContent: "center", gap: 20, marginBottom: 20,
    }}>
      <Phone label="Closed" colors={colors}>
        <FakeApp colors={colors} />
        {sides.map(side => <Pill key={side} pill={pill} side={side} colors={colors} />)}
      </Phone>
      <Phone label="Open" colors={colors}>
        <FakeApp colors={colors} />
        <MiniPanel pill={pill} colors={colors} frosted={blur} />
      </Phone>
    </View>
  );
}

function Phone({ label, colors, children }: { label: string; colors: ColorScheme; children: ReactNode }) {
  return (
    <View style={{ alignItems: "center", gap: 8 }}>
      <View style={{
        width: PHONE_W, height: PHONE_H, borderRadius: 22, overflow: "hidden",
        backgroundColor: colors.surface, borderWidth: 3, borderColor: colors.outlineVariant,
      }}>
        {children}
      </View>
      <Text style={[type.labelMedium, { color: colors.onSurfaceVariant }]}>{label}</Text>
    </View>
  );
}

function FakeApp({ colors }: { colors: ColorScheme }) {
  const bar = (w: number, h = 8) => (
    <View style={{ width: w, height: h, borderRadius: h / 2, backgroundColor: colors.surfaceContainerHighest }} />
  );
  return (
    <View style={{ padding: 12, gap: 7 }}>
      {bar(54, 10)}
      <View style={{ height: 42, borderRadius: 10, backgroundColor: colors.tertiaryContainer, marginVertical: 4 }} />
      {bar(80)}{bar(64)}{bar(72)}
      <View style={{ height: 30, borderRadius: 10, backgroundColor: colors.secondaryContainer, marginTop: 4 }} />
      {bar(60)}{bar(76)}
    </View>
  );
}

function usePillGeometry(pill: PillSettings) {
  const h = Math.max(8, pill.height * SCALE);
  const top = useSharedValue(pill.position * (PHONE_H - 6 - h));
  const height = useSharedValue(h);
  const width = useSharedValue(Math.max(1.5, pill.width * SCALE));
  useEffect(() => {
    top.value = withSpring(pill.position * (PHONE_H - 6 - h), Springs.spatial);
    height.value = withSpring(h, Springs.spatial);
    width.value = withSpring(Math.max(1.5, pill.width * SCALE), Springs.spatial);
  }, [pill.position, pill.width, h, top, height, width]);
  return { top, height, width };
}

function Pill({ pill, side, colors }: { pill: PillSettings; side: "left" | "right"; colors: ColorScheme }) {
  const { top, height, width } = usePillGeometry(pill);
  const style = useAnimatedStyle(() => ({
    top: top.value, height: height.value, width: width.value, borderRadius: width.value / 2,
  }));
  return (
    <Animated.View style={[{
      position: "absolute", [side]: 1, backgroundColor: colors.primary, opacity: Math.max(0.08, pill.opacity),
    }, style]} />
  );
}

function MiniPanel({ pill, colors, frosted }: { pill: PillSettings; colors: ColorScheme; frosted: boolean }) {
  const surface = frosted ? alpha(colors.surfaceContainer, 0.65) : colors.surfaceContainer;
  const side = pill.side === "left" ? "left" : "right";
  const panelH = 92;
  const panelTop = Math.min(PHONE_H - 6 - panelH, Math.max(4, pill.position * (PHONE_H - 6) - panelH / 2));
  const top = useSharedValue(panelTop);
  useEffect(() => { top.value = withSpring(panelTop, Springs.spatial); }, [panelTop, top]);
  const style = useAnimatedStyle(() => ({ top: top.value }));
  const dot = (bg: string, key: number) => (
    <View key={key} style={{ width: 12, height: 12, borderRadius: 6, backgroundColor: bg }} />
  );
  const tiles = [colors.primary, colors.surfaceContainerHighest, colors.primary,
    colors.surfaceContainerHighest, colors.surfaceContainerHighest, colors.secondaryContainer];
  return (
    <Animated.View style={[{
      position: "absolute", [side]: 4, flexDirection: side === "right" ? "row-reverse" : "row", gap: 3,
      alignItems: "flex-start",
    }, style]}>
      <View style={{
        width: 20, height: panelH, borderRadius: 10, backgroundColor: surface,
        alignItems: "center", paddingTop: 4, gap: 5, elevation: 3,
      }}>
        <View style={{ width: 13, height: 13, borderRadius: 4, backgroundColor: colors.primary }} />
        <View style={{ width: 8, height: 2, borderRadius: 1, backgroundColor: colors.outlineVariant }} />
        {[colors.tertiary, colors.secondary, colors.primaryContainer, colors.tertiaryContainer, colors.inversePrimary].map(dot)}
      </View>
      <View style={{
        width: 48, padding: 4, borderRadius: 8, backgroundColor: surface,
        flexDirection: "row", flexWrap: "wrap", gap: 3, elevation: 3,
      }}>
        {tiles.map((bg, i) => (
          <View key={i} style={{ width: 11.3, height: 11.3, borderRadius: bg === colors.primary ? 6 : 3, backgroundColor: bg }} />
        ))}
      </View>
    </Animated.View>
  );
}
