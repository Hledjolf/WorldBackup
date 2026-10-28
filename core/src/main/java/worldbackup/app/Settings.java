package worldbackup.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import worldbackup.core.Json;
import worldbackup.core.RetentionPolicy;
import worldbackup.core.Store;
import worldbackup.core.StoreSettings;

/**
 * WorldBackup's {@code config.json} in the server's data folder (design/settings.md). World settings, with
 * per-world overrides under {@code Worlds.<name>}, and the store settings, which belong to the store's
 * {@code store.json} and are only mirrored here so Mod Settings Manager can edit them.
 */
public final class Settings {
    /** Store settings: kept in store.json, shared by every server on the machine. */
    public static final List<String> STORE_KEYS = List.of("MaxTotalMB", "MinFreeMB", "WarnAtPercent");

    /** World settings and their defaults, in the sidecar's order. */
    public static final Map<String, Object> WORLD_DEFAULTS = new LinkedHashMap<>();

    static {
        WORLD_DEFAULTS.put("ScheduledSnapshots", true);
        WORLD_DEFAULTS.put("ScheduleMinutes", 60L);
        WORLD_DEFAULTS.put("SkipUnchanged", true);
        WORLD_DEFAULTS.put("OnlyWhilePlayersOnline", true);
        WORLD_DEFAULTS.put("MaxCopyMBps", 100L);
        WORLD_DEFAULTS.put("KeepScheduled", 24L);
        WORLD_DEFAULTS.put("KeepDaily", 7L);
        WORLD_DEFAULTS.put("KeepWeekly", 4L);
        WORLD_DEFAULTS.put("KeepShutdown", 2L);
        WORLD_DEFAULTS.put("PreUpdateSnapshot", true);
        WORLD_DEFAULTS.put("ModChangeSnapshot", true);
        WORLD_DEFAULTS.put("KeepMilestones", 3L);
        WORLD_DEFAULTS.put("ExcludeWorlds", "");
        WORLD_DEFAULTS.put("BackupDir", "");
        WORLD_DEFAULTS.put("IncludePlayers", true);
        WORLD_DEFAULTS.put("AnnounceToOps", true);
    }

    /** Server-wide keys a world can't override. */
    static final List<String> SERVER_ONLY = List.of("BackupDir", "ExcludeWorlds");

    /** The settings in effect for one world, and where each came from. */
    public static final class World {
        public final Map<String, Object> values = new LinkedHashMap<>();
        public final Map<String, Object> from = new LinkedHashMap<>();

        public boolean bool(String key) {
            return Boolean.TRUE.equals(values.get(key));
        }

        public int integer(String key) {
            Object v = values.get(key);
            return v instanceof Number ? (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, ((Number) v).longValue())) : 0;
        }

        public String string(String key) {
            Object v = values.get(key);
            return v == null ? "" : v.toString();
        }

        public RetentionPolicy policy() {
            RetentionPolicy p = new RetentionPolicy();
            p.keepScheduled = Math.max(1, integer("KeepScheduled"));
            p.keepDaily = Math.max(0, integer("KeepDaily"));
            p.keepWeekly = Math.max(0, integer("KeepWeekly"));
            p.keepShutdown = Math.max(1, integer("KeepShutdown"));
            p.keepMilestones = Math.max(0, integer("KeepMilestones"));
            return p;
        }

        /** {@code ScheduleMinutes}, never under the five-minute floor. */
        public int scheduleMinutes() {
            return Math.max(5, integer("ScheduleMinutes"));
        }
    }

    private final Path file;
    private Map<String, Object> raw = new LinkedHashMap<>();
    private String problem;

    public Settings(Path dataDir) {
        this.file = dataDir.resolve("config.json");
    }

    public Path file() {
        return file;
    }

    /** Re-reads the file. A file that isn't valid JSON keeps the last good values, and {@link #problem()} says why. */
    public void load() throws IOException {
        if (!Files.exists(file)) {
            raw = new LinkedHashMap<>();
            problem = null;
            return;
        }
        try {
            raw = Json.parseObject(Files.readString(file, StandardCharsets.UTF_8));
            problem = null;
        } catch (RuntimeException e) {
            problem = file + " isn't valid JSON (" + e.getMessage() + "); using the last good settings";
        }
    }

    public String problem() {
        return problem;
    }

    /** A value as written in the file, or null. */
    public Object raw(String key) {
        return raw.get(key);
    }

    /** Sets one top-level value in the file (null removes it), keeping everything else as it is. */
    public void putRaw(String key, Object value) throws IOException {
        Map<String, Object> out = new LinkedHashMap<>(raw);
        if (value == null) out.remove(key);
        else out.put(key, value);
        Files.createDirectories(file.getParent());
        Store.writeAtomic(file, Json.write(out));
        raw = out;
    }

    /**
     * Writes every world key that's missing with its default, and the store's current settings, so Mod
     * Settings Manager shows real values. Keys the file already has, and keys WorldBackup doesn't know, are
     * kept as they are. Returns true if the file changed.
     */
    public boolean fillIn(StoreSettings store) throws IOException {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> storeValues = storeValues(store);
        boolean changed = !Files.exists(file);
        for (String k : STORE_KEYS) {
            out.put(k, storeValues.get(k));
            if (!storeValues.get(k).equals(raw.get(k))) changed = true;
        }
        for (Map.Entry<String, Object> e : WORLD_DEFAULTS.entrySet()) {
            if (raw.containsKey(e.getKey())) {
                out.put(e.getKey(), raw.get(e.getKey()));
            } else {
                out.put(e.getKey(), e.getValue());
                changed = true;
            }
        }
        for (Map.Entry<String, Object> e : raw.entrySet()) out.putIfAbsent(e.getKey(), e.getValue());
        if (!changed) return false;
        Files.createDirectories(file.getParent());
        Store.writeAtomic(file, Json.write(out));
        raw = out;
        return true;
    }

    /** The store settings as written in this file, falling back to {@code current} for missing or bad values. */
    public StoreSettings storeSettings(StoreSettings current) {
        StoreSettings s = new StoreSettings();
        s.maxTotalMB = clamp(raw.get("MaxTotalMB"), 100, 10_000_000, current.maxTotalMB);
        s.minFreeMB = clamp(raw.get("MinFreeMB"), 0, 1_000_000, current.minFreeMB);
        s.warnAtPercent = (int) clamp(raw.get("WarnAtPercent"), 50, 100, current.warnAtPercent);
        return s;
    }

    static Map<String, Object> storeValues(StoreSettings s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("MaxTotalMB", s.maxTotalMB);
        m.put("MinFreeMB", s.minFreeMB);
        m.put("WarnAtPercent", (long) s.warnAtPercent);
        return m;
    }

    /** The settings in effect for {@code world}: its override, else the server's value, else the default. */
    public World world(String world) {
        World w = new World();
        Map<String, Object> override = override(world);
        for (Map.Entry<String, Object> e : WORLD_DEFAULTS.entrySet()) {
            String k = e.getKey();
            Object v;
            String from;
            if (override != null && override.containsKey(k) && !SERVER_ONLY.contains(k) && fits(override.get(k), e.getValue())) {
                v = override.get(k);
                from = "world override";
            } else if (raw.containsKey(k) && fits(raw.get(k), e.getValue())) {
                v = raw.get(k);
                from = "config";
            } else {
                v = e.getValue();
                from = "default";
            }
            w.values.put(k, v);
            w.from.put(k, from);
        }
        return w;
    }

    /** The server's own settings (no override). */
    public World server() {
        return world(null);
    }

    /** The {@code Worlds} key for a world: its name with '.' replaced, since a dot would split a dotted key. */
    public static String overrideKey(String world) {
        return world.replace('.', '_');
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> override(String world) {
        if (world == null) return null;
        Object worlds = raw.get("Worlds");
        if (!(worlds instanceof Map)) return null;
        Object o = ((Map<String, Object>) worlds).get(overrideKey(world));
        return o instanceof Map ? (Map<String, Object>) o : null;
    }

    /** {@code ExcludeWorlds}, split on commas, trimmed. */
    public List<String> excludedWorlds() {
        List<String> out = new ArrayList<>();
        for (String s : server().string("ExcludeWorlds").split(",")) {
            if (!s.trim().isEmpty()) out.add(s.trim());
        }
        return out;
    }

    public boolean excluded(String world, String gameWorld) {
        for (String x : excludedWorlds()) {
            if (x.equalsIgnoreCase(world) || x.equalsIgnoreCase(gameWorld)) return true;
        }
        return false;
    }

    private static boolean fits(Object value, Object dflt) {
        if (dflt instanceof Boolean) return value instanceof Boolean;
        if (dflt instanceof Long) return value instanceof Number && ((Number) value).doubleValue() == Math.rint(((Number) value).doubleValue());
        return value instanceof String;
    }

    private static long clamp(Object v, long min, long max, long dflt) {
        if (!(v instanceof Number)) return dflt;
        return Math.max(min, Math.min(max, ((Number) v).longValue()));
    }
}
