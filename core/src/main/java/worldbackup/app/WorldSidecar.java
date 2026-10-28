package worldbackup.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import worldbackup.core.FileListing;
import worldbackup.core.Json;
import worldbackup.core.Store;

/**
 * The Mod Settings Manager sidecar WorldBackup writes into its own data folder on a server with several
 * worlds, so each world's overrides get their own rows (design/settings.md, "Per-world settings"): the jar's
 * sidecar without its hidden {@code Worlds} entry, plus a section per world listing each world setting as the
 * dotted key {@code Worlds.<name>.<key>}, with the server's current value as its default. MSM shows a key
 * missing from the file with its default and "not in the file yet", which is exactly "inherited".
 *
 * <p>The file is rewritten only while it's missing or still the one WorldBackup wrote last (its hash is kept in
 * config.json as {@code GeneratedSidecarHash}); a sidecar a server owner wrote or edited is left alone.
 */
public final class WorldSidecar {
    public static final String FILE = "Settings/Hledjolf_WorldBackup.settings.json";
    static final String HASH_KEY = "GeneratedSidecarHash";
    /** Server-wide settings: they mean nothing for one world. */
    static final List<String> SERVER_ONLY = List.of("ExcludeWorlds", "BackupDir", "AnnounceToOps");

    public enum Outcome { WRITTEN, UNCHANGED, REMOVED, NOT_NEEDED, LEFT_ALONE }

    private WorldSidecar() {
    }

    /**
     * Writes, refreshes or removes the generated sidecar in {@code dataDir}. {@code jarSidecar} is the jar's
     * sidecar text; {@code worlds} this server's worlds by store name. With fewer than two worlds there's
     * nothing to override, and a generated file is removed so the jar's sidecar applies again.
     */
    public static Outcome update(Path dataDir, Settings settings, String jarSidecar, List<String> worlds) throws IOException {
        Path file = dataDir.resolve(FILE);
        Object recorded = settings.raw(HASH_KEY);
        boolean ours = !Files.exists(file) || (recorded != null && recorded.equals(hash(Files.readAllBytes(file))));
        if (!ours) return Outcome.LEFT_ALONE;
        if (worlds.size() < 2) {
            if (!Files.exists(file)) return Outcome.NOT_NEEDED;
            Files.delete(file);
            settings.putRaw(HASH_KEY, null);
            return Outcome.REMOVED;
        }
        String text = generate(jarSidecar, settings, worlds);
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (Files.exists(file) && java.util.Arrays.equals(bytes, Files.readAllBytes(file))) return Outcome.UNCHANGED;
        Files.createDirectories(file.getParent());
        Store.writeAtomic(file, text);
        settings.putRaw(HASH_KEY, hash(bytes));
        return Outcome.WRITTEN;
    }

    /** The generated sidecar's text. */
    @SuppressWarnings("unchecked")
    static String generate(String jarSidecar, Settings settings, List<String> worlds) {
        Map<String, Object> jar = Json.parseObject(jarSidecar);
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : jar.entrySet()) {
            if (!e.getKey().equals("sections") && !e.getKey().equals("keys")) out.put(e.getKey(), e.getValue());
        }
        List<Object> sections = new ArrayList<>(Json.list(jar, "sections"));
        Map<String, Object> jarKeys = Json.obj(jar, "keys");
        Map<String, Object> keys = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : jarKeys.entrySet()) {
            if (!e.getKey().equals("Worlds")) keys.put(e.getKey(), e.getValue());
        }
        Settings.World server = settings.server();
        int n = 0;
        for (String world : worlds) {
            String section = "world-" + (++n);
            Map<String, Object> sec = new LinkedHashMap<>();
            sec.put("id", section);
            sec.put("label", "World: " + world);
            sec.put("description", "Values not set here follow the server's settings.");
            sections.add(sec);
            for (String k : Settings.WORLD_DEFAULTS.keySet()) {
                if (SERVER_ONLY.contains(k) || !(jarKeys.get(k) instanceof Map)) continue;
                Map<String, Object> spec = new LinkedHashMap<>((Map<String, Object>) jarKeys.get(k));
                spec.put("section", section);
                spec.put("default", server.values.get(k));
                keys.put("Worlds." + Settings.overrideKey(world) + "." + k, spec);
            }
        }
        out.put("sections", sections);
        out.put("keys", keys);
        return Json.write(out);
    }

    static String hash(byte[] bytes) {
        return FileListing.hex(FileListing.newDigest().digest(bytes));
    }
}
