import { Ionicons } from "@expo/vector-icons";
import { Dispatch, SetStateAction, useEffect, useMemo, useState } from "react";
import { Pressable, Text, View } from "react-native";
import Animated, { useAnimatedStyle, useSharedValue, withSpring } from "react-native-reanimated";
import Sidebar, { OverlaySettings, PillSettings } from "../../modules/sidebar";
import { ButtonGroup } from "../components/ButtonGroup";
import { ColorPickerSheet } from "../components/ColorPickerSheet";
import { Button, Chip } from "../components/Expressive";
import { ListGroup, ListItem, SliderItem, SwitchItem } from "../components/List";
import { OverlayPreview } from "../components/OverlayPreview";
import { Springs } from "../motion";
import { ColorScheme, PALETTE_STYLES, PaletteStyle, SEED_PRESETS, schemeFromSeed, type } from "../theme";
import { useAppTheme } from "../ThemeContext";

type Theme = { seed: string; style: PaletteStyle };

/** Theme ideas from the wallpaper's dominant colors, like the Android wallpaper picker. */
function suggestThemes(wallpaper: string[]): Theme[] {
  const [a, b, c] = wallpaper;
  const ideas: Theme[] = [];
  if (a) ideas.push({ seed: a, style: "tonal" }, { seed: a, style: "vibrant" });
  if (b) ideas.push({ seed: b, style: "tonal" });
  if (a) ideas.push({ seed: a, style: "expressive" });
  if (c) ideas.push({ seed: c, style: "tonal" });
  const seen = new Set<string>();
  return ideas.filter(t => {
    const k = `${t.seed.toUpperCase()}:${t.style}`;
    if (seen.has(k)) return false;
    seen.add(k);
    return true;
  }).slice(0, 5);
}

export function LookTab({ pill, setPill, overlay, setOverlay, blurSupported }: {
  pill: PillSettings;
  setPill: Dispatch<SetStateAction<PillSettings>>;
  overlay: OverlaySettings;
  setOverlay: Dispatch<SetStateAction<OverlaySettings>>;
  blurSupported: boolean;
}) {
  const {
    appTheme, themeChoice, setThemeChoice, colorSource, setColorSource,
    seedColor, setSeedColor, paletteStyle, setPaletteStyle, systemSchemes,
  } = useAppTheme();
  const [pickerOpen, setPickerOpen] = useState(false);
  const [wallpaper, setWallpaper] = useState<string[]>([]);
  const dark = appTheme === "dark";

  useEffect(() => { Sidebar.getWallpaperColors().then(setWallpaper).catch(() => setWallpaper([])); }, []);

  const suggestions = useMemo(() => suggestThemes(wallpaper), [wallpaper]);
  const seed = seedColor.toUpperCase();
  const isCustom = colorSource === "custom";
  const inSuggestions = suggestions.some(t => t.seed.toUpperCase() === seed && t.style === paletteStyle);
  const inPresets = SEED_PRESETS.includes(seed);
  const pickTheme = (t: Theme) => { setColorSource("custom"); setSeedColor(t.seed.toUpperCase()); setPaletteStyle(t.style); };

  return (
    <>
      <OverlayPreview pill={pill} blur={overlay.blurBehind && blurSupported} />

      <ListGroup title="Appearance">
        <ListItem icon="contrast-outline" title="Theme">
          <View style={{ marginTop: 12 }}>
            <ButtonGroup
              value={themeChoice}
              onChange={setThemeChoice}
              options={[
                { value: "system", label: "Auto", icon: "phone-portrait-outline" },
                { value: "light", label: "Light", icon: "sunny-outline" },
                { value: "dark", label: "Dark", icon: "moon-outline" },
              ]}
            />
          </View>
        </ListItem>

        <ListItem
          icon="color-palette-outline"
          title="Colors"
          subtitle={!isCustom
            ? (systemSchemes ? "Dynamic — follows your wallpaper" : "Dynamic colors need Android 12+")
            : "Custom theme"}
        >
          <SectionLabel text="Wallpaper" />
          <View style={{ flexDirection: "row", flexWrap: "wrap", gap: 10, marginLeft: 38 }}>
            <Swatch
              scheme={systemSchemes ? (dark ? systemSchemes.dark : systemSchemes.light) : null}
              badge="sparkles"
              selected={!isCustom}
              onPress={() => setColorSource("system")}
              label="Dynamic"
            />
            {suggestions.map(t => (
              <Swatch
                key={`${t.seed}:${t.style}`}
                scheme={schemeFromSeed(t.seed, t.style)[dark ? "dark" : "light"]}
                selected={isCustom && seed === t.seed.toUpperCase() && paletteStyle === t.style}
                onPress={() => pickTheme(t)}
              />
            ))}
          </View>

          <SectionLabel text="Basic colors" />
          <View style={{ flexDirection: "row", flexWrap: "wrap", gap: 10, marginLeft: 38 }}>
            {SEED_PRESETS.map(p => (
              <Swatch
                key={p}
                scheme={schemeFromSeed(p, isCustom && seed === p ? paletteStyle : "tonal")[dark ? "dark" : "light"]}
                selected={isCustom && seed === p && !inSuggestions}
                onPress={() => pickTheme({ seed: p, style: isCustom && inPresets ? paletteStyle : "tonal" })}
              />
            ))}
          </View>

          <SectionLabel text="Any color" />
          <View style={{ flexDirection: "row", alignItems: "center", gap: 12, marginLeft: 38 }}>
            {isCustom && !inPresets && !inSuggestions && (
              <Swatch scheme={schemeFromSeed(seed, paletteStyle)[dark ? "dark" : "light"]} selected onPress={() => setPickerOpen(true)} />
            )}
            <Button label={isCustom && !inPresets && !inSuggestions ? seed : "Pick a color"}
              icon="color-filter-outline" variant="tonal" onPress={() => setPickerOpen(true)} />
          </View>
        </ListItem>

        {isCustom && (
          <ListItem key="style" title="Palette style" inset>
            <View style={{ flexDirection: "row", flexWrap: "wrap", gap: 8, marginTop: 12, marginLeft: 38 }}>
              {PALETTE_STYLES.map(s => (
                <Chip key={s.id} label={s.label} selected={paletteStyle === s.id} onPress={() => setPaletteStyle(s.id)} />
              ))}
            </View>
          </ListItem>
        )}

        <SwitchItem
          key="blur"
          icon="water-outline"
          title="Background blur"
          subtitle={blurSupported
            ? "Frosted glass background for the panels"
            : "Needs Android 12+ with blur enabled (off in battery saver)"}
          value={overlay.blurBehind}
          onValueChange={v => setOverlay(p => ({ ...p, blurBehind: v }))}
        />
        {overlay.blurBehind && (
          <SliderItem
            key="blurRadius"
            title="Blur strength"
            display={`${overlay.blurRadius}`}
            min={4} max={48} step={1}
            value={overlay.blurRadius}
            startLabel="Soft" endLabel="Strong"
            onChange={v => setOverlay(p => ({ ...p, blurRadius: Math.round(v) }))}
          />
        )}
      </ListGroup>

      <ListGroup title="Handle">
        <ListItem icon="swap-horizontal-outline" title="Side">
          <View style={{ marginTop: 12 }}>
            <ButtonGroup
              value={pill.side}
              onChange={side => setPill(p => ({ ...p, side }))}
              options={[
                { value: "left", label: "Left" },
                { value: "right", label: "Right" },
                { value: "both", label: "Both" },
              ]}
            />
          </View>
        </ListItem>
        <SliderItem icon="locate-outline" title="Position" min={0} max={1} step={0.01}
          value={pill.position} display={`${Math.round(pill.position * 100)}%`}
          startLabel="Top" endLabel="Bottom"
          onChange={v => setPill(p => ({ ...p, position: v }))} />
        <SliderItem icon="resize-outline" title="Height" min={40} max={200} step={1}
          value={pill.height} display={`${Math.round(pill.height)}`}
          onChange={v => setPill(p => ({ ...p, height: Math.round(v) }))} />
        <SliderItem icon="remove-outline" title="Width" min={2} max={40} step={1}
          value={pill.width} display={`${Math.round(pill.width)}`}
          onChange={v => setPill(p => ({ ...p, width: Math.round(v) }))} />
        <SliderItem icon="eye-outline" title="Opacity" min={0} max={1} step={0.05}
          value={pill.opacity} display={`${Math.round(pill.opacity * 100)}%`}
          onChange={v => setPill(p => ({ ...p, opacity: parseFloat(v.toFixed(2)) }))} />
      </ListGroup>

      <ColorPickerSheet
        visible={pickerOpen}
        initial={seed}
        onClose={() => setPickerOpen(false)}
        onApply={hex => { setColorSource("custom"); setSeedColor(hex); }}
      />
    </>
  );
}

function SectionLabel({ text }: { text: string }) {
  const { colors } = useAppTheme();
  return (
    <Text style={[type.labelMedium, { color: colors.onSurfaceVariant, marginTop: 16, marginBottom: 8, marginLeft: 38 }]}>
      {text}
    </Text>
  );
}

/**
 * Theme swatch in the style of the Android wallpaper picker: primary on top, secondary
 * and tertiary below. Selection morphs the circle into a rounded square.
 */
function Swatch({ scheme, selected, onPress, badge, label }: {
  scheme: ColorScheme | null;
  selected: boolean;
  onPress: () => void;
  badge?: keyof typeof Ionicons.glyphMap;
  label?: string;
}) {
  const { colors } = useAppTheme();
  const sel = useSharedValue(selected ? 1 : 0);
  useEffect(() => { sel.value = withSpring(selected ? 1 : 0, Springs.spatial); }, [selected, sel]);
  const shapeStyle = useAnimatedStyle(() => ({ borderRadius: 24 - 10 * sel.value }));
  const ringStyle = useAnimatedStyle(() => ({ borderRadius: 28 - 10 * sel.value, opacity: sel.value }));
  const s = scheme ?? schemeFromSeed("#6750A4").light;

  return (
    <Pressable onPress={onPress} hitSlop={4} accessibilityRole="button" accessibilityLabel={label}
      accessibilityState={{ selected }}>
      <View style={{ width: 56, height: 56, alignItems: "center", justifyContent: "center" }}>
        <Animated.View style={[{
          position: "absolute", width: 56, height: 56, borderWidth: 2, borderColor: colors.primary,
        }, ringStyle]} />
        <Animated.View style={[{ width: 48, height: 48, overflow: "hidden" }, shapeStyle]}>
          <View style={{ flex: 1, backgroundColor: s.primary }} />
          <View style={{ flex: 1, flexDirection: "row" }}>
            <View style={{ flex: 1, backgroundColor: s.secondaryContainer }} />
            <View style={{ flex: 1, backgroundColor: s.tertiary }} />
          </View>
        </Animated.View>
        {(selected || badge) && (
          <View style={{
            position: "absolute", width: 22, height: 22, borderRadius: 11,
            backgroundColor: s.primaryContainer, alignItems: "center", justifyContent: "center",
          }}>
            <Ionicons name={selected ? "checkmark" : badge!} size={13} color={s.onPrimaryContainer} />
          </View>
        )}
      </View>
    </Pressable>
  );
}
