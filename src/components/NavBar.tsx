import { Ionicons } from "@expo/vector-icons";
import { useEffect } from "react";
import { Pressable, Text, View } from "react-native";
import Animated, { useAnimatedStyle, useSharedValue, withSpring } from "react-native-reanimated";
import { useSafeAreaInsets } from "react-native-safe-area-context";
import { Springs } from "../motion";
import { type } from "../theme";
import { useAppTheme } from "../ThemeContext";

type IconName = keyof typeof Ionicons.glyphMap;
export type NavItem<T extends string> = { id: T; label: string; icon: IconName; activeIcon: IconName };

/** M3 Expressive navigation bar: the active indicator springs open from the icon's center. */
export function NavBar<T extends string>({ items, active, onChange }: {
  items: NavItem<T>[]; active: T; onChange: (id: T) => void;
}) {
  const { colors } = useAppTheme();
  const insets = useSafeAreaInsets();
  return (
    <View style={{
      flexDirection: "row", backgroundColor: colors.surfaceContainer,
      paddingTop: 12, paddingBottom: 12 + insets.bottom,
    }}>
      {items.map(item => (
        <NavButton key={item.id} item={item} active={item.id === active} onPress={() => onChange(item.id)} />
      ))}
    </View>
  );
}

function NavButton<T extends string>({ item, active, onPress }: { item: NavItem<T>; active: boolean; onPress: () => void }) {
  const { colors } = useAppTheme();
  const p = useSharedValue(active ? 1 : 0);
  useEffect(() => { p.value = withSpring(active ? 1 : 0, Springs.spatial); }, [active, p]);
  const indicator = useAnimatedStyle(() => ({
    opacity: Math.min(1, p.value * 1.5),
    transform: [{ scaleX: 0.4 + 0.6 * p.value }],
  }));
  return (
    <Pressable style={{ flex: 1, alignItems: "center", gap: 4 }} onPress={onPress} accessibilityRole="tab"
      accessibilityState={{ selected: active }}>
      <View style={{ width: 56, height: 32, alignItems: "center", justifyContent: "center" }}>
        <Animated.View style={[{
          position: "absolute", width: 56, height: 32, borderRadius: 16, backgroundColor: colors.secondaryContainer,
        }, indicator]} />
        <Ionicons
          name={active ? item.activeIcon : item.icon}
          size={22}
          color={active ? colors.onSecondaryContainer : colors.onSurfaceVariant}
        />
      </View>
      <Text style={[type.labelMedium, { color: active ? colors.secondary : colors.onSurfaceVariant }]}>
        {item.label}
      </Text>
    </Pressable>
  );
}
