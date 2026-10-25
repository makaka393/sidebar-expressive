import { Ionicons } from "@expo/vector-icons";
import { useEffect } from "react";
import { Pressable } from "react-native";
import Animated, {
  interpolateColor,
  useAnimatedStyle,
  useSharedValue,
  withSpring,
} from "react-native-reanimated";
import { Springs } from "../motion";
import { useAppTheme } from "../ThemeContext";

const TRACK_W = 52;
const TRACK_H = 32;
const BORDER = 2;
const INNER_H = TRACK_H - BORDER * 2;

/** M3 switch with icon handle: grows when selected, swells while pressed, springs between ends. */
export function M3Switch({ value, onValueChange, disabled }: {
  value: boolean;
  onValueChange?: (v: boolean) => void;
  disabled?: boolean;
}) {
  const { colors } = useAppTheme();
  const progress = useSharedValue(value ? 1 : 0);
  const pressed = useSharedValue(0);

  useEffect(() => {
    progress.value = withSpring(value ? 1 : 0, Springs.spatialFast);
  }, [value, progress]);

  const trackStyle = useAnimatedStyle(() => ({
    backgroundColor: interpolateColor(progress.value, [0, 1], [colors.surfaceContainerHighest, colors.primary]),
    borderColor: interpolateColor(progress.value, [0, 1], [colors.outline, colors.primary]),
  }));

  const handleStyle = useAnimatedStyle(() => {
    const p = Math.min(Math.max(progress.value, 0), 1);
    const size = 16 + 8 * p + (pressed.value * (28 - 16 - 8 * p));
    const center = 14 + 20 * progress.value;
    return {
      width: size,
      height: size,
      borderRadius: size / 2,
      left: center - size / 2,
      top: (INNER_H - size) / 2,
      backgroundColor: interpolateColor(progress.value, [0, 1], [colors.outline, colors.onPrimary]),
    };
  });

  const onIcon = useAnimatedStyle(() => ({ opacity: progress.value, transform: [{ scale: progress.value }] }));

  return (
    <Pressable
      accessibilityRole="switch"
      accessibilityState={{ checked: value, disabled }}
      disabled={disabled}
      hitSlop={8}
      onPressIn={() => { pressed.value = withSpring(1, Springs.spatialFast); }}
      onPressOut={() => { pressed.value = withSpring(0, Springs.spatialFast); }}
      onPress={() => onValueChange?.(!value)}
      style={{ opacity: disabled ? 0.38 : 1 }}
    >
      <Animated.View style={[{
        width: TRACK_W, height: TRACK_H, borderRadius: TRACK_H / 2, borderWidth: BORDER,
      }, trackStyle]}>
        <Animated.View style={[{ position: "absolute", alignItems: "center", justifyContent: "center" }, handleStyle]}>
          <Animated.View style={onIcon}>
            <Ionicons name="checkmark" size={16} color={colors.onPrimaryContainer} />
          </Animated.View>
        </Animated.View>
      </Animated.View>
    </Pressable>
  );
}
