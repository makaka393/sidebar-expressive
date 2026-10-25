import { Ionicons } from "@expo/vector-icons";
import { Dispatch, SetStateAction, useEffect, useMemo } from "react";
import { Linking, Pressable, Text, View } from "react-native";
import DraggableFlatList, { RenderItemParams, ScaleDecorator } from "react-native-draggable-flatlist";
import Animated, { interpolateColor, useAnimatedStyle, useSharedValue, withSpring } from "react-native-reanimated";
import Sidebar, { OverlaySettings } from "../../modules/sidebar";
import { Button } from "../components/Expressive";
import { ListGroup, ListItem, SwitchItem } from "../components/List";
import { Springs } from "../motion";
import { type } from "../theme";
import { useAppTheme } from "../ThemeContext";

type IconName = keyof typeof Ionicons.glyphMap;

export const CONTROL_TILES = [
  { id: 'torch',      label: 'Torch',           key: 'showTorch',          icon: 'flashlight-outline'    },
  { id: 'rotate',     label: 'Auto-rotate',     key: 'showAutoRotate',     icon: 'sync-outline'          },
  { id: 'brightness', label: 'Auto-bright',     key: 'showAutoBrightness', icon: 'contrast-outline'      },
  { id: 'ringer',     label: 'Ringer',          key: 'showRingerMode',     icon: 'volume-medium-outline' },
  { id: 'share',      label: 'Quick Share',     key: 'showQuickShare',     icon: 'share-social-outline'  },
  { id: 'power',      label: 'Power',           key: 'showPower',          icon: 'power-outline'         },
  { id: 'qr',         label: 'QR scan',         key: 'showQr',             icon: 'qr-code-outline'       },
  { id: 'dnd',        label: 'Do Not Disturb',  key: 'showDnd',            icon: 'moon-outline'          },
] as const;

const EXTRA_CONTROLS = [
  { label: 'Brightness', key: 'showBrightnessSlider', icon: 'sunny-outline'   },
  { label: 'Volume',     key: 'showVolume',           icon: 'options-outline' },
] as const;

const ALL_CONTROLS = [...CONTROL_TILES, ...EXTRA_CONTROLS];

type Tile = typeof CONTROL_TILES[number];
type TileKey = Tile['key'];
type AllKey = typeof ALL_CONTROLS[number]['key'];

export function ControlTab({ overlay, setOverlay, perms }: {
  overlay: OverlaySettings;
  setOverlay: Dispatch<SetStateAction<OverlaySettings>>;
  perms: { overlay: boolean | null; dnd: boolean; write: boolean; usage: boolean };
}) {
  const { colors } = useAppTheme();

  const orderedTiles = useMemo(() => {
    const order = overlay.controlTilesOrder ?? [];
    const seen = new Set(order);
    return [
      ...order.map(id => CONTROL_TILES.find(t => t.id === id)).filter((t): t is Tile => !!t),
      ...CONTROL_TILES.filter(t => !seen.has(t.id)),
    ];
  }, [overlay.controlTilesOrder]);

  const activeTiles = orderedTiles.filter(t => overlay[t.key as TileKey]);
  const granted = [perms.overlay, perms.dnd, perms.write, perms.usage].filter(Boolean).length;

  const permRows: { title: string; icon: IconName; ok: boolean; need: string; request: () => void }[] = [
    { title: "Display over other apps", icon: "layers-outline", ok: !!perms.overlay, need: "Required to show the handle", request: Sidebar.requestOverlayPermission },
    { title: "Do Not Disturb", icon: "moon-outline", ok: perms.dnd, need: "Required for the DND tile", request: Sidebar.requestDndPermission },
    { title: "Modify system settings", icon: "settings-outline", ok: perms.write, need: "Required for rotation and brightness", request: Sidebar.requestWriteSettingsPermission },
    { title: "Usage access", icon: "time-outline", ok: perms.usage, need: "Required for recent apps", request: Sidebar.requestUsageAccessPermission },
  ];

  return (
    <>
      <ListGroup title="Quick controls">
        <SwitchItem icon="grid-outline" title="Quick controls"
          subtitle="Tiles in the Tools panel"
          value={overlay.quickControlsEnabled}
          onValueChange={v => setOverlay(p => ({ ...p, quickControlsEnabled: v }))} />

        {overlay.quickControlsEnabled && (
          <ListItem key="order" title="Order" subtitle="Long-press a tile and drag" inset>
            <View style={{ marginTop: 12, marginHorizontal: -20 }}>
              <DraggableFlatList
                horizontal
                data={activeTiles}
                keyExtractor={item => item.id}
                showsHorizontalScrollIndicator={false}
                contentContainerStyle={{ paddingHorizontal: 20, gap: 8 }}
                renderItem={({ item, drag, isActive }: RenderItemParams<Tile>) => (
                  <ScaleDecorator activeScale={1.08}>
                    <Pressable onLongPress={drag} delayLongPress={150} style={{ alignItems: "center", width: 68 }}>
                      <View style={{
                        width: 56, height: 56, borderRadius: isActive ? 18 : 28,
                        backgroundColor: isActive ? colors.primary : colors.primaryContainer,
                        alignItems: "center", justifyContent: "center",
                      }}>
                        <Ionicons name={item.icon as IconName} size={22}
                          color={isActive ? colors.onPrimary : colors.onPrimaryContainer} />
                      </View>
                      <Text numberOfLines={1} style={[type.labelSmall, { color: colors.onSurfaceVariant, marginTop: 6 }]}>
                        {item.label}
                      </Text>
                    </Pressable>
                  </ScaleDecorator>
                )}
                onDragEnd={({ data }) => {
                  const disabledIds = orderedTiles.filter(t => !overlay[t.key as TileKey]).map(t => t.id);
                  setOverlay(p => ({ ...p, controlTilesOrder: [...data.map(t => t.id), ...disabledIds] }));
                }}
              />
            </View>
          </ListItem>
        )}

        {overlay.quickControlsEnabled && (
          <ListItem key="all" title="Available" subtitle="Tap to add or remove" inset>
            <View style={{ flexDirection: "row", flexWrap: "wrap", marginTop: 8, marginHorizontal: -12 }}>
              {ALL_CONTROLS.map(item => (
                <ToggleTile
                  key={item.key}
                  label={item.label}
                  icon={item.icon as IconName}
                  on={!!overlay[item.key as AllKey]}
                  onPress={() => setOverlay(p => ({ ...p, [item.key]: !p[item.key as AllKey] }))}
                />
              ))}
            </View>
          </ListItem>
        )}
      </ListGroup>

      <ListGroup title={`Permissions · ${granted} of 4`}>
        {permRows.map(r => (
          <ListItem
            key={r.title}
            icon={r.icon}
            title={r.title}
            subtitle={r.ok ? "Granted" : r.need}
            trailing={r.ok
              ? <Ionicons name="checkmark-circle" size={24} color={colors.primary} />
              : <Button label="Grant" variant="tonal" onPress={r.request} />}
          />
        ))}
      </ListGroup>

      <Button label="Open app settings" icon="open-outline" variant="tonal"
        onPress={() => Linking.openSettings()} style={{ alignSelf: "center", marginBottom: 8 }} />
    </>
  );
}

/** Icon toggle: round when off, morphs to a filled rounded square when on. */
function ToggleTile({ label, icon, on, onPress }: { label: string; icon: IconName; on: boolean; onPress: () => void }) {
  const { colors } = useAppTheme();
  const p = useSharedValue(on ? 1 : 0);
  const pressed = useSharedValue(0);
  useEffect(() => { p.value = withSpring(on ? 1 : 0, Springs.spatial); }, [on, p]);
  const style = useAnimatedStyle(() => ({
    borderRadius: (28 - 12 * p.value) * (1 - 0.3 * pressed.value),
    transform: [{ scale: 1 - 0.06 * pressed.value }],
    backgroundColor: interpolateColor(p.value, [0, 1], [colors.surfaceContainerHighest, colors.primary]),
  }));
  return (
    <Pressable
      style={{ width: "25%", alignItems: "center", paddingVertical: 8 }}
      onPress={onPress}
      onPressIn={() => { pressed.value = withSpring(1, Springs.spatialFast); }}
      onPressOut={() => { pressed.value = withSpring(0, Springs.spatial); }}
      accessibilityRole="switch"
      accessibilityState={{ checked: on }}
    >
      <Animated.View style={[{ width: 56, height: 56, alignItems: "center", justifyContent: "center" }, style]}>
        <Ionicons name={icon} size={22} color={on ? colors.onPrimary : colors.onSurfaceVariant} />
      </Animated.View>
      <Text numberOfLines={1} style={[type.labelSmall, { color: on ? colors.onSurface : colors.onSurfaceVariant, marginTop: 6 }]}>
        {label}
      </Text>
    </Pressable>
  );
}
