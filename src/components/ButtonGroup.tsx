import { Ionicons } from "@expo/vector-icons";
import { useEffect } from "react";
import { Pressable, View } from "react-native";
import Animated, {
  interpolateColor,
  useAnimatedStyle,
  useSharedValue,
  withSpring,
} from "react-native-reanimated";
import { Springs } from "../motion";
import { type } from "../theme";
import { useAppTheme } from "../ThemeContext";

export type GroupOption<T extends string> = { value: T; label: string; icon?: keyof typeof Ionicons.glyphMap };

const H = 44;
const FULL = H / 2;
const INNER = 8;

/**
 * M3 Expressive connected button group (single select). The selected button
 * morphs to a full pill; pressing squeezes the corners.
 */
export function ButtonGroup<T extends string>({ options, value, onChange }: {
  options: GroupOption<T>[];
  value: T;
  onChange: (v: T) => void;
}) {
  return (
    <View style={{ flexDirection: "row", gap: 2 }}>
      {options.map((o, i) => (
        <Segment
          key={o.value}
          option={o}
          selected={o.value === value}
          first={i === 0}
          last={i === options.length - 1}
          onPress={() => onChange(o.value)}
        />
      ))}
    </View>
  );
}

function Segment<T extends string>({ option, selected, first, last, onPress }: {
  option: GroupOption<T>; selected: boolean; first: boolean; last: boolean; onPress: () => void;
}) {
  const { colors } = useAppTheme();
  const sel = useSharedValue(selected ? 1 : 0);
  const pressed = useSharedValue(0);

  useEffect(() => { sel.value = withSpring(selected ? 1 : 0, Springs.spatial); }, [selected, sel]);

  const style = useAnimatedStyle(() => {
    const inner = (INNER + (FULL - INNER) * sel.value) * (1 - 0.45 * pressed.value);
    const outer = FULL * (1 - 0.45 * pressed.value);
    return {
      borderTopLeftRadius: first ? outer : inner,
      borderBottomLeftRadius: first ? outer : inner,
      borderTopRightRadius: last ? outer : inner,
      borderBottomRightRadius: last ? outer : inner,
      backgroundColor: interpolateColor(sel.value, [0, 1], [colors.surfaceContainerHighest, colors.primary]),
    };
  });

  const fg = selected ? colors.onPrimary : colors.onSurfaceVariant;

  return (
    <Pressable
      style={{ flex: 1 }}
      onPress={onPress}
      onPressIn={() => { pressed.value = withSpring(1, Springs.spatialFast); }}
      onPressOut={() => { pressed.value = withSpring(0, Springs.spatialFast); }}
      accessibilityRole="button"
      accessibilityState={{ selected }}
    >
      <Animated.View style={[{
        height: H, flexDirection: "row", alignItems: "center", justifyContent: "center",
        gap: 6, paddingHorizontal: 8,
      }, style]}>
        {option.icon && <Ionicons name={option.icon} size={18} color={fg} />}
        <Animated.Text numberOfLines={1} style={[type.labelLarge, { color: fg }]}>{option.label}</Animated.Text>
      </Animated.View>
    </Pressable>
  );
}
