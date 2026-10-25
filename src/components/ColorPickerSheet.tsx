import { useEffect, useState } from "react";
import { Modal, Pressable, Text, View } from "react-native";
import { GestureHandlerRootView } from "react-native-gesture-handler";
import Animated, { FadeIn, FadeOut, SlideInDown, SlideOutDown } from "react-native-reanimated";
import ColorPicker, { HueSlider, InputWidget, Panel1, Preview } from "reanimated-color-picker";
import { useSafeAreaInsets } from "react-native-safe-area-context";
import { emphasizedDecelerate } from "../motion";
import { alpha, isHex, schemeFromSeed, type } from "../theme";
import { useAppTheme } from "../ThemeContext";
import { Button } from "./Expressive";

/**
 * Desktop-style color picker in a bottom sheet: saturation/brightness field, hue bar,
 * old/new preview, HEX/RGB/HSL inputs, and a live preview of the scheme the color makes.
 */
export function ColorPickerSheet({ visible, initial, onClose, onApply }: {
  visible: boolean;
  initial: string;
  onClose: () => void;
  onApply: (hex: string) => void;
}) {
  const { colors, appTheme, paletteStyle } = useAppTheme();
  const insets = useSafeAreaInsets();
  const [current, setCurrent] = useState(isHex(initial) ? initial : "#6750A4");

  useEffect(() => { if (visible) setCurrent(isHex(initial) ? initial : "#6750A4"); }, [visible, initial]);

  const scheme = schemeFromSeed(current, paletteStyle)[appTheme === "dark" ? "dark" : "light"];
  const inputStyle = [type.bodyMedium, {
    color: colors.onSurface, borderColor: colors.outlineVariant, borderRadius: 12,
    backgroundColor: colors.surfaceContainerHigh, paddingVertical: 6,
  }];

  return (
    <Modal visible={visible} transparent statusBarTranslucent navigationBarTranslucent animationType="none" onRequestClose={onClose}>
      <GestureHandlerRootView style={{ flex: 1, justifyContent: "flex-end" }}>
        {visible && (
          <Animated.View entering={FadeIn.duration(200)} exiting={FadeOut.duration(150)}
            style={{ position: "absolute", top: 0, left: 0, right: 0, bottom: 0, backgroundColor: alpha("#000000", 0.32) }}>
            <Pressable style={{ flex: 1 }} onPress={onClose} accessibilityLabel="Close color picker" />
          </Animated.View>
        )}
        {visible && (
          <Animated.View
            entering={SlideInDown.duration(380).easing(emphasizedDecelerate)}
            exiting={SlideOutDown.duration(200)}
            style={{
              backgroundColor: colors.surfaceContainerLow, borderTopLeftRadius: 28, borderTopRightRadius: 28,
              paddingHorizontal: 24, paddingTop: 12, paddingBottom: 16 + insets.bottom,
            }}
          >
            <View style={{ alignSelf: "center", width: 32, height: 4, borderRadius: 2, backgroundColor: colors.onSurfaceVariant, opacity: 0.4, marginBottom: 16 }} />
            <Text style={[type.titleLarge, { color: colors.onSurface, marginBottom: 16 }]}>Pick any color</Text>

            <ColorPicker
              value={current}
              onCompleteJS={({ hex }) => setCurrent(hex.slice(0, 7).toUpperCase())}
              thumbSize={28}
              sliderThickness={28}
              boundedThumb
            >
              <Panel1 style={{ height: 180, borderRadius: 20 }} thumbShape="ring" />
              <HueSlider style={{ marginTop: 16, borderRadius: 14 }} thumbShape="ring" />
              <View style={{ flexDirection: "row", alignItems: "center", gap: 12, marginTop: 16 }}>
                <Preview style={{ width: 72, height: 40, borderRadius: 20 }} hideInitialColor={false} hideText />
                <View style={{ flex: 1 }}>
                  <InputWidget
                    defaultFormat="HEX"
                    formats={["HEX", "RGB", "HSL"]}
                    disableAlphaChannel
                    inputStyle={inputStyle}
                    inputTitleStyle={[type.labelSmall, { color: colors.onSurfaceVariant }]}
                    iconColor={colors.onSurfaceVariant}
                  />
                </View>
              </View>
            </ColorPicker>

            <View style={{ flexDirection: "row", alignItems: "center", gap: 8, marginTop: 20 }}>
              <Text style={[type.labelMedium, { color: colors.onSurfaceVariant, marginRight: 4 }]}>Theme</Text>
              {[scheme.primary, scheme.secondary, scheme.tertiary, scheme.primaryContainer, scheme.surfaceContainerHighest].map((c, i) => (
                <View key={i} style={{ width: 28, height: 28, borderRadius: 14, backgroundColor: c, borderWidth: 1, borderColor: colors.outlineVariant }} />
              ))}
            </View>

            <View style={{ flexDirection: "row", justifyContent: "flex-end", gap: 8, marginTop: 24 }}>
              <Button label="Cancel" variant="tonal" onPress={onClose} />
              <Button label="Apply" icon="checkmark" onPress={() => { onApply(current); onClose(); }} />
            </View>
          </Animated.View>
        )}
      </GestureHandlerRootView>
    </Modal>
  );
}

