package worldbackup.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What changed since a world's last clean shutdown: the game build ({@code last-build.json}) and the mod list
 * ({@code last-mods.json}), both in the server's own data folder (not the store: they describe that server). Decides the {@code pre-update} and
 * {@code mod-change} milestones before the game opens the world.
 */
public final class BootCheck {
    public static final class Mod {
        public final String id;
        public final String version;
        public final String sha256;

        public Mod(String id, String version, String sha256) {
            this.id = id;
            this.version = version;
            this.sha256 = sha256;
        }

        @Override
        public String toString() {
            return version == null ? id : id + " " + version;
        }
    }

    public static final class Result {
        /** No record at all: first run with WorldBackup, or the state was lost. */
        public boolean firstRun;
        public String lastBuild;
        public boolean buildChanged;
        public final List<Mod> added = new ArrayList<>();
        public final List<Mod> removed = new ArrayList<>();
        public final List<Mod> updated = new ArrayList<>();

        public boolean modsChanged() {
            return !added.isEmpty() || !removed.isEmpty() || !updated.isEmpty();
        }

        /** The mod-change diff for a manifest's extras: {"added": [...], "removed": [...], "updated": [...]}. */
        public Map<String, Object> modsJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("added", names(added));
            m.put("removed", names(removed));
            m.put("updated", names(updated));
            return m;
        }

        private static List<Object> names(List<Mod> mods) {
            List<Object> out = new ArrayList<>();
            for (Mod x : mods) out.add(x.toString());
            return out;
        }
    }

    private final Path dir;

    /** {@code dataDir}: the server's or save's own folder, {@code mods/Hledjolf_WorldBackup/} in Hytale. */
    public BootCheck(Path dataDir) {
        this.dir = dataDir;
    }

    /** The build recorded at the last clean shutdown (or after the last snapshots before loading), or null. */
    public String lastBuild() throws IOException {
        Path buildFile = dir.resolve("last-build.json");
        if (!Files.exists(buildFile)) return null;
        return Json.str(Json.parseObject(Files.readString(buildFile, StandardCharsets.UTF_8)), "build");
    }

    public Result check(String currentBuild, List<Mod> currentMods) throws IOException {
        Result r = new Result();
        Path buildFile = dir.resolve("last-build.json");
        Path modsFile = dir.resolve("last-mods.json");
        if (!Files.exists(buildFile)) {
            r.firstRun = true;
            return r;
        }
        r.lastBuild = Json.str(Json.parseObject(Files.readString(buildFile, StandardCharsets.UTF_8)), "build");
        r.buildChanged = currentBuild != null && !currentBuild.equals(r.lastBuild);
        if (Files.exists(modsFile)) {
            Map<String, Mod> before = readMods(modsFile);
            Map<String, Mod> now = new LinkedHashMap<>();
            for (Mod m : currentMods) now.put(m.id, m);
            for (Mod m : now.values()) {
                Mod b = before.get(m.id);
                if (b == null) r.added.add(m);
                else if (!same(b.version, m.version) || !same(b.sha256, m.sha256)) r.updated.add(m);
            }
            for (Mod b : before.values()) {
                if (!now.containsKey(b.id)) r.removed.add(b);
            }
        }
        return r;
    }

    /**
     * The worlds that already got their snapshot before loading for this build and mod list, when an earlier
     * boot snapshotted some worlds and failed on others (design/Hytale.md, "Catching the upgrade"). They aren't
     * snapshotted again: they may already be upgraded. Empty when the record is for another build or mod list.
     */
    public java.util.Set<String> snapshottedFor(String build, List<Mod> mods) throws IOException {
        java.util.Set<String> out = new java.util.HashSet<>();
        Path f = dir.resolve("boot-snapshots.json");
        if (!Files.exists(f)) return out;
        try {
            Map<String, Object> m = Json.parseObject(Files.readString(f, StandardCharsets.UTF_8));
            if (!same(Json.str(m, "build"), build) || !same(Json.str(m, "mods"), modsKey(mods))) return out;
            for (Object o : Json.list(m, "worlds")) out.add(o.toString());
        } catch (RuntimeException unreadable) {
            // treated as no record: at worst one more snapshot
        }
        return out;
    }

    /** Records that {@code world} got its snapshot before loading for this build and mod list. */
    public void snapshotted(String build, List<Mod> mods, String world) throws IOException {
        java.util.Set<String> done = snapshottedFor(build, mods);
        done.add(world);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("build", build);
        m.put("mods", modsKey(mods));
        List<Object> ws = new ArrayList<>(new java.util.TreeSet<>(done));
        m.put("worlds", ws);
        Store.writeAtomic(dir.resolve("boot-snapshots.json"), Json.write(m));
    }

    /** A short fingerprint of a mod list: ids, versions and hashes, in id order. */
    static String modsKey(List<Mod> mods) {
        List<String> parts = new ArrayList<>();
        for (Mod m : mods) parts.add(m.id + "@" + m.version + "@" + m.sha256);
        java.util.Collections.sort(parts);
        return FileListing.hex(FileListing.newDigest().digest(String.join("\n", parts).getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * Records the build and mod list: right after every world got its snapshot before loading, and at each
     * clean shutdown. Clears the per-world record.
     */
    public void record(String build, List<Mod> mods) throws IOException {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("build", build);
        Store.writeAtomic(dir.resolve("last-build.json"), Json.write(b));
        List<Object> list = new ArrayList<>();
        for (Mod m : mods) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("id", m.id);
            e.put("version", m.version);
            e.put("sha256", m.sha256);
            list.add(e);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("mods", list);
        Store.writeAtomic(dir.resolve("last-mods.json"), Json.write(root));
        Files.deleteIfExists(dir.resolve("boot-snapshots.json"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Mod> readMods(Path f) throws IOException {
        Map<String, Mod> out = new LinkedHashMap<>();
        for (Object o : Json.list(Json.parseObject(Files.readString(f, StandardCharsets.UTF_8)), "mods")) {
            Map<String, Object> e = (Map<String, Object>) o;
            Mod m = new Mod(Json.str(e, "id"), Json.str(e, "version"), Json.str(e, "sha256"));
            out.put(m.id, m);
        }
        return out;
    }

    private static boolean same(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }
}
