import { Ionicons } from "@expo/vector-icons";
import { Image } from "expo-image";
import { Dispatch, memo, SetStateAction, useCallback, useEffect, useMemo } from "react";
import { ActivityIndicator, FlatList, Pressable, Text, TextInput, View } from "react-native";
import DraggableFlatList, { RenderItemParams, ScaleDecorator } from "react-native-draggable-flatlist";
import Animated, { useAnimatedStyle, useSharedValue, withSpring, ZoomIn, ZoomOut } from "react-native-reanimated";
import { InstalledApp } from "../../modules/sidebar";
import { Button } from "../components/Expressive";
import { Springs } from "../motion";
import { type } from "../theme";
import { useAppTheme } from "../ThemeContext";

export type FavItem = { key: string; name: string; icon: string | null };

export function AppsTab({ allApps, loading, favorites, setFavorites, query, setQuery, dirty, saving, onSave }: {
  allApps: InstalledApp[];
  loading: boolean;
  favorites: FavItem[];
  setFavorites: Dispatch<SetStateAction<FavItem[]>>;
  query: string;
  setQuery: (q: string) => void;
  dirty: boolean;
  saving: boolean;
  onSave: () => void;
}) {
  const { colors } = useAppTheme();
  const favSet = useMemo(() => new Set(favorites.map(i => i.key)), [favorites]);
  const filtered = useMemo(() => {
    const q = query.toLowerCase();
    return q ? allApps.filter(a => a.name.toLowerCase().includes(q)) : allApps;
  }, [allApps, query]);

  const toggleFav = useCallback((app: InstalledApp) => {
    setFavorites(prev =>
      prev.some(i => i.key === app.packageName)
        ? prev.filter(i => i.key !== app.packageName)
        : [...prev, { key: app.packageName, name: app.name, icon: app.icon }]
    );
  }, [setFavorites]);

  const renderApp = useCallback(({ item }: { item: InstalledApp }) => (
    <AppCell app={item} selected={favSet.has(item.packageName)} onPress={toggleFav} />
  ), [favSet, toggleFav]);

  const renderFav = useCallback(({ item, drag, isActive }: RenderItemParams<FavItem>) => (
    <ScaleDecorator activeScale={1.04}>
      <Pressable
        onLongPress={drag}
        delayLongPress={150}
        style={{
          flexDirection: "row", alignItems: "center", gap: 10, height: 60,
          paddingHorizontal: 10, marginBottom: 4, borderRadius: isActive ? 20 : 14,
          backgroundColor: isActive ? colors.secondaryContainer : colors.surfaceBright,
        }}
      >
        {item.icon
          ? <Image source={{ uri: `data:image/png;base64,${item.icon}` }} style={{ width: 36, height: 36 }} contentFit="contain" />
          : <View style={{ width: 36, height: 36, borderRadius: 18, backgroundColor: colors.surfaceContainerHighest }} />}
        <Text numberOfLines={1} style={[type.bodyMedium, { flex: 1, color: colors.onSurface }]}>{item.name}</Text>
        <Ionicons name="reorder-two" size={20} color={colors.onSurfaceVariant} />
      </Pressable>
    </ScaleDecorator>
  ), [colors]);

  return (
    <View style={{ flex: 1, flexDirection: "row", gap: 8, paddingHorizontal: 12 }}>
      <View style={{ flex: 6 }}>
        <View style={{
          flexDirection: "row", alignItems: "center", gap: 10, height: 52, borderRadius: 26,
          paddingHorizontal: 16, marginBottom: 8, backgroundColor: colors.surfaceContainerHigh,
        }}>
          <Ionicons name="search" size={20} color={colors.onSurfaceVariant} />
          <TextInput
            style={[type.bodyLarge, { flex: 1, color: colors.onSurface, paddingVertical: 0 }]}
            placeholder="Search apps"
            placeholderTextColor={colors.onSurfaceVariant}
            value={query}
            onChangeText={setQuery}
          />
          {query.length > 0 && (
            <Pressable onPress={() => setQuery("")} hitSlop={10}>
              <Ionicons name="close" size={20} color={colors.onSurfaceVariant} />
            </Pressable>
          )}
        </View>
        {loading ? (
          <ActivityIndicator style={{ marginTop: 32 }} color={colors.primary} size="large" />
        ) : (
          <FlatList
            data={filtered}
            keyExtractor={item => item.packageName}
            renderItem={renderApp}
            numColumns={3}
            extraData={favSet}
            contentContainerStyle={{ paddingBottom: 16 }}
            showsVerticalScrollIndicator={false}
            keyboardShouldPersistTaps="handled"
          />
        )}
      </View>

      <View style={{
        flex: 4, backgroundColor: colors.surfaceContainerHigh, borderRadius: 24, padding: 8, marginBottom: 8,
      }}>
        <Text style={[type.labelLarge, { color: colors.primary, margin: 8, marginTop: 6 }]}>
          Favorites · {favorites.length}
        </Text>
        <DraggableFlatList
          data={favorites}
          keyExtractor={item => item.key}
          renderItem={renderFav}
          onDragEnd={({ data }) => setFavorites(data)}
          containerStyle={{ flex: 1 }}
          showsVerticalScrollIndicator={false}
          ListEmptyComponent={
            <View style={{ alignItems: "center", marginTop: 24, gap: 8, paddingHorizontal: 8 }}>
              <Ionicons name="apps-outline" size={28} color={colors.onSurfaceVariant} />
              <Text style={[type.bodySmall, { color: colors.onSurfaceVariant, textAlign: "center" }]}>
                Tap apps on the left to add them
              </Text>
            </View>
          }
        />
        <Button
          label={saving ? "Saving…" : dirty ? "Save" : "Saved"}
          icon={dirty ? "save-outline" : "checkmark"}
          variant={dirty ? "filled" : "tonal"}
          disabled={saving || !dirty}
          onPress={onSave}
          style={{ marginTop: 8, height: 48 }}
        />
      </View>
    </View>
  );
}

const AppCell = memo(function AppCell({ app, selected, onPress }: {
  app: InstalledApp; selected: boolean; onPress: (a: InstalledApp) => void;
}) {
  const { colors } = useAppTheme();
  const sel = useSharedValue(selected ? 1 : 0);
  const pressed = useSharedValue(0);
  useEffect(() => { sel.value = withSpring(selected ? 1 : 0, Springs.spatial); }, [selected, sel]);
  const bg = useAnimatedStyle(() => ({
    opacity: sel.value,
    borderRadius: 32 - 14 * sel.value,
    transform: [{ scale: 0.7 + 0.3 * sel.value }],
  }));
  const icon = useAnimatedStyle(() => ({
    transform: [{ scale: (1 - 0.12 * sel.value) * (1 - 0.08 * pressed.value) }],
  }));
  return (
    <Pressable
      style={{ flex: 1, alignItems: "center", paddingVertical: 8, paddingHorizontal: 2 }}
      onPress={() => onPress(app)}
      onPressIn={() => { pressed.value = withSpring(1, Springs.spatialFast); }}
      onPressOut={() => { pressed.value = withSpring(0, Springs.spatial); }}
    >
      <View style={{ width: 64, height: 64, alignItems: "center", justifyContent: "center" }}>
        <Animated.View style={[{
          position: "absolute", width: 64, height: 64, backgroundColor: colors.primaryContainer,
        }, bg]} />
        <Animated.View style={icon}>
          <Image source={{ uri: `data:image/png;base64,${app.icon}` }} style={{ width: 48, height: 48 }} contentFit="contain" />
        </Animated.View>
        {selected && (
          <Animated.View
            entering={ZoomIn.springify().stiffness(Springs.spatialFast.stiffness).damping(Springs.spatialFast.damping)}
            exiting={ZoomOut.duration(120)}
            style={{
              position: "absolute", bottom: 0, right: 0, width: 22, height: 22, borderRadius: 11,
              backgroundColor: colors.primary, alignItems: "center", justifyContent: "center",
              borderWidth: 2, borderColor: colors.surfaceContainer,
            }}
          >
            <Ionicons name="checkmark" size={12} color={colors.onPrimary} />
          </Animated.View>
        )}
      </View>
      <Text numberOfLines={2} style={[type.labelSmall, {
        color: selected ? colors.primary : colors.onSurfaceVariant, textAlign: "center", marginTop: 4,
      }]}>
        {app.name}
      </Text>
    </Pressable>
  );
});
