import { Ionicons } from "@expo/vector-icons";
import { Children, ReactNode } from "react";
import { Pressable, Text, View } from "react-native";
import Animated, { FadeIn, FadeOut, LinearTransition } from "react-native-reanimated";
import { emphasizedDecelerate } from "../motion";
import { alpha, type } from "../theme";
import { useAppTheme } from "../ThemeContext";
import { M3Slider } from "./M3Slider";
import { M3Switch } from "./M3Switch";

type IconName = keyof typeof Ionicons.glyphMap;

const OUTER = 24;
const INNER = 6;
const layout = LinearTransition.duration(260).easing(emphasizedDecelerate);

/**
 * Segmented M3 Expressive list: items are separate tiles with a 2dp gap,
 * large outer corners and tight inner corners. Rows added later fade in and
 * the rest of the group eases into place.
 */
export function ListGroup({ title, children }: { title?: string; children: ReactNode }) {
  const { colors } = useAppTheme();
  const items = Children.toArray(children);
  return (
    <Animated.View layout={layout} style={{ marginBottom: 20 }}>
      {title && (
        <Text style={[type.labelLarge, { color: colors.primary, marginHorizontal: 16, marginBottom: 8 }]}>
          {title}
        </Text>
      )}
      <View style={{ gap: 2 }}>
        {items.map((child, i) => (
          <Animated.View
            key={(child as { key?: string }).key ?? i}
            entering={FadeIn.duration(220)}
            exiting={FadeOut.duration(120)}
            layout={layout}
            style={{
              backgroundColor: colors.surfaceBright,
              overflow: "hidden",
              borderTopLeftRadius: i === 0 ? OUTER : INNER,
              borderTopRightRadius: i === 0 ? OUTER : INNER,
              borderBottomLeftRadius: i === items.length - 1 ? OUTER : INNER,
              borderBottomRightRadius: i === items.length - 1 ? OUTER : INNER,
            }}
          >
            {child}
          </Animated.View>
        ))}
      </View>
    </Animated.View>
  );
}

export function ListItem({ icon, title, subtitle, trailing, onPress, children, inset }: {
  icon?: IconName;
  title: string;
  subtitle?: string;
  trailing?: ReactNode;
  onPress?: () => void;
  children?: ReactNode;
  /** Indents the row to sit under a parent item (no leading icon). */
  inset?: boolean;
}) {
  const { colors } = useAppTheme();
  const body = (
    <View style={{ paddingHorizontal: 20, paddingVertical: 14, minHeight: 64, justifyContent: "center" }}>
      <View style={{ flexDirection: "row", alignItems: "center", gap: 16 }}>
        {icon && <Ionicons name={icon} size={22} color={colors.primary} />}
        {inset && <View style={{ width: 22 }} />}
        <View style={{ flex: 1 }}>
          <Text style={[type.bodyLarge, { color: colors.onSurface }]}>{title}</Text>
          {subtitle ? <Text style={[type.bodyMedium, { color: colors.onSurfaceVariant, marginTop: 2 }]}>{subtitle}</Text> : null}
        </View>
        {trailing}
      </View>
      {children}
    </View>
  );
  if (!onPress) return body;
  return (
    <Pressable onPress={onPress} android_ripple={{ color: alpha(colors.onSurface, 0.1) }}>
      {body}
    </Pressable>
  );
}

export function SwitchItem({ value, onValueChange, ...rest }: {
  icon?: IconName; title: string; subtitle?: string; inset?: boolean;
  value: boolean; onValueChange: (v: boolean) => void;
}) {
  return (
    <ListItem
      {...rest}
      onPress={() => onValueChange(!value)}
      trailing={<M3Switch value={value} onValueChange={onValueChange} />}
    />
  );
}

export function SliderItem({ icon, title, display, min, max, step, value, onChange, startLabel, endLabel }: {
  icon?: IconName; title: string; display: string;
  min: number; max: number; step: number; value: number;
  onChange: (v: number) => void;
  startLabel?: string; endLabel?: string;
}) {
  const { colors } = useAppTheme();
  return (
    <ListItem
      icon={icon}
      title={title}
      trailing={<Text style={[type.labelLarge, { color: colors.primary, minWidth: 40, textAlign: "right" }]}>{display}</Text>}
    >
      <View style={{ marginTop: 6, marginLeft: icon ? 38 : 0 }}>
        <M3Slider min={min} max={max} step={step} value={value} onChange={onChange} />
        {(startLabel || endLabel) && (
          <View style={{ flexDirection: "row", justifyContent: "space-between", marginTop: -4 }}>
            <Text style={[type.labelSmall, { color: colors.onSurfaceVariant }]}>{startLabel}</Text>
            <Text style={[type.labelSmall, { color: colors.onSurfaceVariant }]}>{endLabel}</Text>
          </View>
        )}
      </View>
    </ListItem>
  );
}
