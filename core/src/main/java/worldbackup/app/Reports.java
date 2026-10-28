package worldbackup.app;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import worldbackup.core.Budget;
import worldbackup.core.FileListing;
import worldbackup.core.Json;
import worldbackup.core.Manifest;
import worldbackup.core.Retention;
import worldbackup.core.RetentionPolicy;
import worldbackup.core.SourceFile;
import worldbackup.core.Store;
import worldbackup.core.StoreSettings;
import worldbackup.core.Trigger;

/** Reading the store for people: lists, the budget check's measurements, and export. No game needed. */
public final class Reports {
    private Reports() {
    }

    /** Every world with snapshots or a source file, by name. */
    public static List<String> storeWorlds(Store store) throws IOException {
        Map<String, Boolean> names = new LinkedHashMap<>();
        for (SourceFile s : store.sources()) names.put(s.world, true);
        for (Manifest m : store.allManifests()) names.put(m.world, true);
        List<String> out = new ArrayList<>(names.keySet());
        out.sort(String::compareToIgnoreCase);
        return out;
    }

    /** One line per snapshot of {@code world}, newest first. */
    public static List<String> list(Store store, String world) throws IOException {
        List<Manifest> ms = store.manifests(world);
        List<String> out = new ArrayList<>();
        if (ms.isEmpty()) {
            out.add("No snapshots of '" + world + "'.");
            return out;
        }
        Retention.Usage u = Retention.Usage.measure(store);
        out.add(ms.size() + " snapshots of '" + world + "', newest first (total size, unique size):");
        for (int i = ms.size() - 1; i >= 0; i--) out.add("  " + line(ms.get(i), u));
        return out;
    }

    public static String line(Manifest m, Retention.Usage u) {
        StringBuilder b = new StringBuilder(m.id);
        b.append("  ").append(m.gameBuild == null ? "?" : m.gameBuild);
        b.append("  ").append(Budget.size(m.totalBytes())).append(", ").append(Budget.size(u.uniqueBytes(m)));
        if (m.label != null) b.append("  \"").append(m.label).append('"');
        Object mods = m.extras.get("mods");
        if (mods instanceof Map) b.append("  ").append(modsText((Map<?, ?>) mods));
        if (m.pinned) b.append("  [pinned]");
        if (!m.consistent) b.append("  [changed while copying]");
        return b.toString();
    }

    static String modsText(Map<?, ?> diff) {
        List<String> parts = new ArrayList<>();
        for (String k : new String[] {"added", "removed", "updated"}) {
            Object v = diff.get(k);
            if (!(v instanceof List)) continue;
            for (Object x : (List<?>) v) parts.add((k.equals("added") ? "+" : k.equals("removed") ? "-" : "~") + x);
        }
        return parts.isEmpty() ? "" : "(" + String.join(", ", parts) + ")";
    }

    /** {@code /worldbackup worlds}: here, elsewhere on this computer, deleted. */
    public static List<String> worlds(Store store, Set<String> here) throws IOException {
        Retention.Usage u = Retention.Usage.measure(store);
        Map<String, Long> unique = new LinkedHashMap<>();
        for (Manifest m : u.manifests) unique.merge(m.world, u.uniqueBytes(m), Long::sum);
        List<String> mine = new ArrayList<>(), others = new ArrayList<>(), gone = new ArrayList<>();
        for (String w : storeWorlds(store)) {
            SourceFile s = store.source(w);
            String space = Budget.size(unique.getOrDefault(w, 0L));
            if (here.contains(w)) {
                mine.add("  " + w + "  " + space);
            } else if (s != null && s.gone()) {
                gone.add("  " + w + "  deleted " + s.sourceGone + ", " + space + " (export brings it back)");
            } else {
                String seen = s == null ? "no source file" : "last seen " + s.lastSeen + ", build " + s.gameBuild + ", " + s.mods.size() + " mods";
                others.add("  " + w + "  " + seen + ", " + space);
            }
        }
        List<String> out = new ArrayList<>();
        out.add("Here:");
        out.addAll(mine.isEmpty() ? List.of("  (none)") : mine);
        if (!others.isEmpty()) {
            out.add("Other worlds on this computer:");
            out.addAll(others);
        }
        if (!gone.isEmpty()) {
            out.add("Deleted worlds:");
            out.addAll(gone);
        }
        out.add("Store: " + store.root() + ", " + Budget.size(u.total) + " of " + Budget.size(store.settings().maxTotalBytes()));
        return out;
    }

    /**
     * The budget check's input, measured from the whole store: every world's size, churn and protected bytes,
     * with each world's own counts from its source file where there is one.
     */
    public static Budget.Input budgetInput(Store store, Settings.World server, long builtInBytes) throws IOException {
        Budget.Input in = new Budget.Input();
        StoreSettings ss = store.settings();
        in.capBytes = ss.maxTotalBytes();
        in.policy = server.policy();
        in.scheduled = server.bool("ScheduledSnapshots");
        in.scheduleMinutes = server.scheduleMinutes();
        in.maxCopyMBps = server.integer("MaxCopyMBps");
        in.builtinBackupBytes = builtInBytes;
        Retention.Usage u = Retention.Usage.measure(store);
        in.storeBytes = u.total;
        try {
            Files.createDirectories(store.root());
            in.freeBytes = Files.getFileStore(store.root()).getUsableSpace();
        } catch (IOException ignored) {
            // unknown: no drive warning
        }
        Map<String, List<Manifest>> byWorld = new LinkedHashMap<>();
        for (Manifest m : u.manifests) byWorld.computeIfAbsent(m.world, k -> new ArrayList<>()).add(m);
        Map<String, RetentionPolicy> policies = new LinkedHashMap<>();
        for (String w : byWorld.keySet()) {
            SourceFile s = store.source(w);
            policies.put(w, s == null || s.settings.isEmpty() ? server.policy() : policyOf(s.settings));
        }
        Set<Manifest> prot = Retention.protectedSnapshots(byWorld, policies);
        for (Map.Entry<String, List<Manifest>> e : byWorld.entrySet()) {
            Budget.World w = new Budget.World();
            w.name = e.getKey();
            List<Manifest> ms = e.getValue();
            w.sizeBytes = ms.get(ms.size() - 1).totalBytes();
            w.policy = policies.get(w.name);
            for (Manifest m : ms) {
                if (m.trigger == Trigger.SCHEDULED) {
                    w.churnSamples.add(m.addedBytes);
                    w.tookMillisSamples.add(m.tookMillis);
                }
                if (m.trigger.autoMilestone) {
                    w.milestoneCount++;
                    w.milestoneBytes += u.uniqueBytes(m);
                }
                if (prot.contains(m)) w.protectedBytes += u.uniqueBytes(m);
            }
            Manifest latest = ms.get(ms.size() - 1);
            w.updatedRecently = latest.gameBuild != null && ms.stream().anyMatch(m -> m.trigger == Trigger.PRE_UPDATE
                && m.createdMillis() > latest.createdMillis() - 7L * 24 * 3600 * 1000);
            in.worlds.add(w);
        }
        return in;
    }

    static RetentionPolicy policyOf(Map<String, Object> settings) {
        Settings.World w = new Settings.World();
        for (Map.Entry<String, Object> e : Settings.WORLD_DEFAULTS.entrySet()) w.values.put(e.getKey(), settings.getOrDefault(e.getKey(), e.getValue()));
        return w.policy();
    }

    /** The budget report as lines: the info line, then each problem. */
    public static List<String> budget(Budget.Report r, String onlyWorld) {
        List<String> out = new ArrayList<>();
        out.add(r.info);
        for (Budget.Problem p : r.problems) {
            if (onlyWorld == null || p.message.contains("'" + onlyWorld + "'") || !p.message.contains("'")) out.add(p.toString());
        }
        if (r.problems.isEmpty()) out.add("These settings fit.");
        return out;
    }

    /**
     * Writes a snapshot out as a plain folder, each block checked against its hash: an ordinary copy of the
     * world, ready to drop where the game keeps its worlds. Refuses a target that already has files.
     */
    public static Path export(Store store, Manifest m, Path target) throws IOException {
        if (Files.isDirectory(target)) {
            try (var s = Files.list(target)) {
                if (s.findAny().isPresent()) throw new IOException(target + " already has files in it");
            }
        }
        MessageDigest md = FileListing.newDigest();
        for (Manifest.FileEntry f : m.files) {
            Path out = target.resolve(f.path).normalize();
            if (!out.startsWith(target.normalize())) throw new IOException("Unsafe path in snapshot: " + f.path);
            Files.createDirectories(out.getParent());
            try (OutputStream os = Files.newOutputStream(out)) {
                for (String b : f.blocks) {
                    byte[] data = store.readBlock(b);
                    md.reset();
                    if (!FileListing.hex(md.digest(data)).equals(b)) throw new IOException("Block " + b + " is damaged (" + f.path + ")");
                    os.write(data);
                }
            }
            Files.setLastModifiedTime(out, FileTime.fromMillis(f.mtimeMillis));
        }
        Map<String, Object> about = new LinkedHashMap<>();
        about.put("snapshot", m.id);
        about.put("world", m.world);
        about.put("source", m.source);
        about.put("build", m.gameBuild);
        Store.writeAtomic(target.resolve("WorldBackup-export.json"), Json.write(about));
        return target;
    }
}
