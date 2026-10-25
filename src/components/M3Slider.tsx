import { useCallback, useEffect, useMemo, useRef } from "react";
import { View } from "react-native";
import { Gesture, GestureDetector } from "react-native-gesture-handler";
import Animated, {
  runOnJS,
  useAnimatedStyle,
  useSharedValue,
  withSpring,
} from "react-native-reanimated";
import { Springs } from "../motion";
import { useAppTheme } from "../ThemeContext";

const HEIGHT = 44;
const TRACK_H = 16;
const HANDLE_W = 4;
const HANDLE_W_PRESSED = 2;
const GAP = 6;
const OUTER_R = TRACK_H / 2;
const INNER_R = 2;

/**
 * M3 Expressive slider: thick split track with a bar handle and a gap on either side.
 * The handle thins while dragging; taps glide to the new value with a spring.
 *
 * Gestures are created once and read everything through shared values / refs, so a
 * parent re-render (which happens on every value change) never re-attaches them mid-drag.
 */
export function M3Slider({ min, max, step, value, onChange, disabled }: {
  min: number; max: number; step: number; value: number;
  onChange: (v: number) => void;
  disabled?: boolean;
}) {
  const { colors } = useAppTheme();
  const width = useSharedValue(0);
  const ratio = useSharedValue((value - min) / (max - min));
  const dragging = useSharedValue(false);
  const pressed = useSharedValue(0);
  const lastEmitted = useSharedValue(value);
  const range = useSharedValue({ min, max, step });

  const onChangeRef = useRef(onChange);
  onChangeRef.current = onChange;
  const emit = useCallback((v: number) => onChangeRef.current(v), []);

  useEffect(() => { range.value = { min, max, step }; }, [min, max, step, range]);

  useEffect(() => {
    if (!dragging.value) ratio.value = withSpring((value - min) / (max - min), Springs.spatial);
    lastEmitted.value = value;
  }, [value, min, max, ratio, dragging, lastEmitted]);

  const gesture = useMemo(() => {
    const setFromX = (x: number, animate: boolean) => {
      "worklet";
      if (width.value <= 0) return;
      const { min: lo, max: hi, step: st } = range.value;
      const raw = Math.min(Math.max(x / width.value, 0), 1);
      const stepped = Math.round((lo + raw * (hi - lo)) / st) * st;
      // Trim float noise from fractional steps (e.g. 0.05).
      const v = Math.min(hi, Math.max(lo, Math.round(stepped * 1e6) / 1e6));
      const r = (v - lo) / (hi - lo);
      ratio.value = animate ? withSpring(r, Springs.spatial) : r;
      if (v !== lastEmitted.value) {
        lastEmitted.value = v;
        runOnJS(emit)(v);
      }
    };

    const pan = Gesture.Pan()
      .activeOffsetX([-4, 4])
      .failOffsetY([-12, 12])
      .onStart(e => {
        dragging.value = true;
        pressed.value = withSpring(1, Springs.spatialFast);
        setFromX(e.x, false);
      })
      .onUpdate(e => setFromX(e.x, false))
      .onFinalize(() => {
        dragging.value = false;
        pressed.value = withSpring(0, Springs.spatialFast);
      });

    const tap = Gesture.Tap()
      .onEnd(e => setFromX(e.x, true));

    return Gesture.Race(pan, tap);
  }, [width, range, ratio, lastEmitted, emit, dragging, pressed]);

  const handleCenter = () => {
    "worklet";
    return HANDLE_W / 2 + ratio.value * (width.value - HANDLE_W);
  };

  const activeStyle = useAnimatedStyle(() => ({
    width: Math.max(0, handleCenter() - HANDLE_W / 2 - GAP),
  }));
  const inactiveStyle = useAnimatedStyle(() => ({
    left: Math.min(width.value, handleCenter() + HANDLE_W / 2 + GAP),
  }));
  const handleStyle = useAnimatedStyle(() => {
    const w = HANDLE_W + (HANDLE_W_PRESSED - HANDLE_W) * pressed.value;
    return { width: w, left: handleCenter() - w / 2 };
  });
  // The stop indicator hides once the handle reaches the end of the track.
  const stopStyle = useAnimatedStyle(() => ({ opacity: ratio.value > 0.97 ? 0 : 1 }));

  return (
    <GestureDetector gesture={gesture}>
      <View
        pointerEvents={disabled ? "none" : "auto"}
        style={{ height: HEIGHT, justifyContent: "center", opacity: disabled ? 0.38 : 1 }}
        onLayout={e => { width.value = e.nativeEvent.layout.width; }}
        accessibilityRole="adjustable"
        accessibilityValue={{ min, max, now: value }}
      >
        <Animated.View style={[{
          position: "absolute", left: 0, top: (HEIGHT - TRACK_H) / 2, height: TRACK_H,
          backgroundColor: colors.primary,
          borderTopLeftRadius: OUTER_R, borderBottomLeftRadius: OUTER_R,
          borderTopRightRadius: INNER_R, borderBottomRightRadius: INNER_R,
        }, activeStyle]} />
        <Animated.View style={[{
          position: "absolute", right: 0, top: (HEIGHT - TRACK_H) / 2, height: TRACK_H,
          backgroundColor: colors.secondaryContainer,
          borderTopRightRadius: OUTER_R, borderBottomRightRadius: OUTER_R,
          borderTopLeftRadius: INNER_R, borderBottomLeftRadius: INNER_R,
          justifyContent: "center", alignItems: "flex-end", paddingRight: 6,
        }, inactiveStyle]}>
          <Animated.View style={[{ width: 4, height: 4, borderRadius: 2, backgroundColor: colors.primary }, stopStyle]} />
        </Animated.View>
        <Animated.View style={[{
          position: "absolute", top: 0, height: HEIGHT, borderRadius: 2, backgroundColor: colors.primary,
        }, handleStyle]} />
      </View>
    </GestureDetector>
  );
}
