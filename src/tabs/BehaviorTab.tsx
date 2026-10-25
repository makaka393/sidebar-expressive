import { Ionicons } from "@expo/vector-icons";
import { Dispatch, SetStateAction, useEffect, useState } from "react";
import { Text, View } from "react-native";
import Animated, { FadeIn, useAnimatedStyle, useSharedValue, withSpring } from "react-native-reanimated";
import Sidebar, { OverlaySettings } from "../../modules/sidebar";
import { Chip } from "../components/Expressive";
import { ListGroup, ListItem, SliderItem, SwitchItem } from "../components/List";
import { Springs } from "../motion";
import { type } from "../theme";
import { useAppTheme } from "../ThemeContext";

type GestureKey = "gestureSwipeIn" | "gestureSwipeUp" | "gestureSwipeDown" | "gestureDoubleTap";

const GESTURES: { key: GestureKey; label: string; icon: keyof typeof Ionicons.glyphMap }[] = [
  { key: "gestureSwipeIn", label: "Swipe in", icon: "arrow-back-outline" },
  { key: "gestureSwipeUp", label: "Swipe up", icon: "arrow-up-outline" },
  { key: "gestureSwipeDown", label: "Swipe down", icon: "arrow-down-outline" },
  { key: "gestureDoubleTap", label: "Double tap", icon: "finger-print-outline" },
];

const ACTIONS = [
  { value: "none", label: "Off" },
  { value: "panel", label: "Panel" },
  { value: "notifications", label: "Notifications" },
  { value: "quick_settings", label: "Quick Settings" },
  { value: "all_apps", label: "All Apps" },
] as const;

export function BehaviorTab({ overlay, setOverlay, usagePerm }: {
  overlay: OverlaySettings;
  setOverlay: Dispatch<SetStateAction<OverlaySettings>>;
  usagePerm: boolean;
}) {
  const [openGesture, setOpenGesture] = useState<GestureKey | null>(null);
  const set = <K extends keyof OverlaySettings>(k: K) => (v: OverlaySettings[K]) =>
    setOverlay(p => ({ ...p, [k]: v }));

  return (
    <>
      <ListGroup title="Panel">
        <SwitchItem icon="time-outline" title="Recent apps"
          subtitle="Show the 4 most recently used apps at the top (needs Usage Access)"
          value={overlay.showRecentApps}
          onValueChange={v => {
            if (v && !usagePerm) { Sidebar.requestUsageAccessPermission(); return; }
            set("showRecentApps")(v);
          }} />
        <SwitchItem icon="text-outline" title="App labels" value={overlay.showLabels} onValueChange={set("showLabels")} />
        <SwitchItem icon="keypad-outline" title="Keyboard on All Apps"
          subtitle="Focus search and show the keyboard when All Apps opens"
          value={overlay.showKeyboardOnAllApps} onValueChange={set("showKeyboardOnAllApps")} />
        <SwitchItem icon="browsers-outline" title="Freeform windows"
          subtitle="Open apps as floating windows via Shizuku"
          value={overlay.freeformWindows}
          onValueChange={async v => {
            if (v) {
              const status = await Sidebar.hasShizukuPermission();
              if (status !== "granted") await Sidebar.requestShizukuPermission();
            }
            set("freeformWindows")(v);
          }} />
      </ListGroup>

      <ListGroup title="Handle">
        <SwitchItem icon="expand-outline" title="Hide in fullscreen"
          subtitle="Hide the pull-tab while an app is fullscreen"
          value={overlay.autoHideFullscreen} onValueChange={set("autoHideFullscreen")} />
        <SwitchItem icon="pulse-outline" title="Vibration feedback" value={overlay.vibration} onValueChange={set("vibration")} />
        <SliderItem icon="speedometer-outline" title="Swipe sensitivity" min={8} max={48} step={1}
          value={overlay.sensitivity} display={`${overlay.sensitivity}`}
          startLabel="High" endLabel="Low"
          onChange={v => set("sensitivity")(Math.round(v))} />
      </ListGroup>

      <ListGroup title="Gestures">
        <SwitchItem icon="hand-left-outline" title="Pill gestures"
          subtitle="Map swipes and taps on the handle to actions"
          value={overlay.gesturesEnabled} onValueChange={set("gesturesEnabled")} />
        {overlay.gesturesEnabled && GESTURES.map(g => (
          <GestureRow
            key={g.key}
            label={g.label}
            icon={g.icon}
            value={overlay[g.key]}
            open={openGesture === g.key}
            onToggle={() => setOpenGesture(o => (o === g.key ? null : g.key))}
            onSelect={v => { set(g.key)(v); setOpenGesture(null); }}
          />
        ))}
      </ListGroup>
    </>
  );
}

function GestureRow({ label, icon, value, open, onToggle, onSelect }: {
  label: string;
  icon: keyof typeof Ionicons.glyphMap;
  value: OverlaySettings[GestureKey];
  open: boolean;
  onToggle: () => void;
  onSelect: (v: OverlaySettings[GestureKey]) => void;
}) {
  const { colors } = useAppTheme();
  const rot = useSharedValue(open ? 1 : 0);
  useEffect(() => { rot.value = withSpring(open ? 1 : 0, Springs.spatial); }, [open, rot]);
  const chevron = useAnimatedStyle(() => ({ transform: [{ rotate: `${rot.value * 180}deg` }] }));
  const current = ACTIONS.find(a => a.value === value)?.label ?? "Off";

  return (
    <ListItem
      icon={icon}
      title={label}
      onPress={onToggle}
      trailing={
        <View style={{ flexDirection: "row", alignItems: "center", gap: 6 }}>
          <Text style={[type.labelLarge, { color: value === "none" ? colors.onSurfaceVariant : colors.primary }]}>
            {current}
          </Text>
          <Animated.View style={chevron}>
            <Ionicons name="chevron-down" size={18} color={colors.onSurfaceVariant} />
          </Animated.View>
        </View>
      }
    >
      {open && (
        <Animated.View entering={FadeIn.duration(200)}
          style={{ flexDirection: "row", flexWrap: "wrap", gap: 8, marginTop: 12, marginLeft: 38 }}>
          {ACTIONS.map(a => (
            <Chip key={a.value} label={a.label} selected={value === a.value} onPress={() => onSelect(a.value)} />
          ))}
        </Animated.View>
      )}
    </ListItem>
  );
}
