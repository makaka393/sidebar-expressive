import { Ionicons } from "@expo/vector-icons";
import { ReactNode } from "react";
import { Pressable, StyleProp, Text, ViewStyle } from "react-native";
import Animated, {
  useAnimatedStyle,
  useSharedValue,
  withSpring,
} from "react-native-reanimated";
import { Springs } from "../motion";
import { alpha, type } from "../theme";
import { useAppTheme } from "../ThemeContext";

/**
 * Pressable whose corners tighten and body dips slightly while held, then
 * springs back — the M3 Expressive "shape morph on press".
 */
export function MorphPressable({ radius, pressedRadius, scale = 0.97, style, onPress, onLongPress, disabled, children, ripple = true }: {
  radius: number;
  pressedRadius: number;
  scale?: number;
  style?: StyleProp<ViewStyle>;
  onPress?: () => void;
  onLongPress?: () => void;
  disabled?: boolean;
  children: ReactNode;
  ripple?: boolean;
}) {
  const { colors } = useAppTheme();
  const pressed = useSharedValue(0);
  const animated = useAnimatedStyle(() => ({
    borderRadius: radius + (pressedRadius - radius) * pressed.value,
    transform: [{ scale: 1 + (scale - 1) * pressed.value }],
  }));
  return (
    <Pressable
      disabled={disabled}
      onPress={onPress}
      onLongPress={onLongPress}
      delayLongPress={200}
      onPressIn={() => { pressed.value = withSpring(1, Springs.spatialFast); }}
      onPressOut={() => { pressed.value = withSpring(0, Springs.spatial); }}
    >
      <Animated.View style={[{ overflow: "hidden" }, style, animated]}>
        {ripple
          ? <Pressable
              pointerEvents="none"
              android_ripple={{ color: alpha(colors.onSurface, 0.1), foreground: true }}
              style={{ flex: 1 }}
            >{children}</Pressable>
          : children}
      </Animated.View>
    </Pressable>
  );
}

/** M3 filled / tonal button with a press morph. */
export function Button({ label, icon, onPress, variant = "filled", disabled, style }: {
  label: string;
  icon?: keyof typeof Ionicons.glyphMap;
  onPress?: () => void;
  variant?: "filled" | "tonal";
  disabled?: boolean;
  style?: StyleProp<ViewStyle>;
}) {
  const { colors } = useAppTheme();
  const bg = variant === "filled" ? colors.primary : colors.secondaryContainer;
  const fg = variant === "filled" ? colors.onPrimary : colors.onSecondaryContainer;
  return (
    <MorphPressable
      radius={20}
      pressedRadius={10}
      onPress={onPress}
      disabled={disabled}
      ripple={false}
      style={[{
        height: 40, paddingHorizontal: 20, backgroundColor: bg, opacity: disabled ? 0.38 : 1,
        flexDirection: "row", alignItems: "center", justifyContent: "center", gap: 8,
      }, style]}
    >
      {icon && <Ionicons name={icon} size={18} color={fg} />}
      <Text style={[type.labelLarge, { color: fg }]}>{label}</Text>
    </MorphPressable>
  );
}

/** M3 filter chip. */
export function Chip({ label, selected, onPress }: { label: string; selected: boolean; onPress: () => void }) {
  const { colors } = useAppTheme();
  return (
    <MorphPressable
      radius={8}
      pressedRadius={16}
      onPress={onPress}
      ripple={false}
      style={{
        height: 32, paddingHorizontal: selected ? 8 : 16,
        flexDirection: "row", alignItems: "center", gap: 6,
        backgroundColor: selected ? colors.secondaryContainer : "transparent",
        borderWidth: selected ? 0 : 1, borderColor: colors.outlineVariant,
      }}
    >
      {selected && <Ionicons name="checkmark" size={18} color={colors.onSecondaryContainer} />}
      <Text style={[type.labelLarge, { color: selected ? colors.onSecondaryContainer : colors.onSurfaceVariant }]}>
        {label}
      </Text>
    </MorphPressable>
  );
}
