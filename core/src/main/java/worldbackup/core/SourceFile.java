package worldbackup.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code sources/<world>.json}: what the server or save that owns a world tells every other copy of WorldBackup
 * using the store (design/README.md, "Sources"). Rewritten under the lock at startup, after every snapshot and
 * prune, when settings change, and at clean shutdown.
 */
public final class SourceFile {
    public static final long FORMAT = 1;

    public String world;
    /** The world's root folder. */
    public String source;
    /** {@code singleplayer} or {@code server}. */
    public String kind;
    public String gameId;
    public String gameBuild;
    public String gameRevision;
    public String gamePatchline;
    public String worldBackup;
    public List<BootCheck.Mod> mods = new ArrayList<>();
    /** Every world setting as in effect. */
    public Map<String, Object> settings = new LinkedHashMap<>();
    /** Where each setting came from: {@code default}, {@code config} or {@code worlds override}. */
    public Map<String, Object> settingsFrom = new LinkedHashMap<>();
    public String lastSnapshot;
    public String lastSeen;
    public boolean running;
    /** The game's own backups, shown and never touched: enabled, bytes, files. */
    public Map<String, Object> builtInBackups;
    /** The date the world's folder was found missing, or null. */
    public String sourceGone;
    /** The snapshot staged for a restore of this world, or null: protected from every server's prune until done. */
    public String pendingRestore;

    public boolean gone() {
        return sourceGone != null;
    }

    /** True when {@code root} is this world's folder: the server or save that may restore, pin and prune it. */
    public boolean ownedBy(Path root) {
        return source != null && samePath(Path.of(source), root);
    }

    public boolean folderExists() {
        return source != null && Files.isDirectory(Path.of(source));
    }

    static boolean samePath(Path a, Path b) {
        String x = a.toAbsolutePath().normalize().toString(), y = b.toAbsolutePath().normalize().toString();
        return java.io.File.separatorChar == '\\' ? x.equalsIgnoreCase(y) : x.equals(y);
    }

    public Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("format", FORMAT);
        m.put("world", world);
        m.put("source", source);
        m.put("kind", kind);
        Map<String, Object> game = new LinkedHashMap<>();
        game.put("id", gameId);
        game.put("build", gameBuild);
        game.put("revision", gameRevision);
        game.put("patchline", gamePatchline);
        m.put("game", game);
        m.put("worldBackup", worldBackup);
        List<Object> ml = new ArrayList<>();
        for (BootCheck.Mod x : mods) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("id", x.id);
            e.put("version", x.version);
            e.put("sha256", x.sha256);
            ml.add(e);
        }
        m.put("mods", ml);
        m.put("settings", settings);
        m.put("settingsFrom", settingsFrom);
        m.put("lastSnapshot", lastSnapshot);
        m.put("lastSeen", lastSeen);
        m.put("running", running);
        m.put("builtInBackups", builtInBackups);
        m.put("sourceGone", sourceGone);
        m.put("pendingRestore", pendingRestore);
        return m;
    }

    @SuppressWarnings("unchecked")
    public static SourceFile fromJson(Map<String, Object> m) {
        long format = Json.num(m, "format", -1);
        if (format != FORMAT) throw new IllegalArgumentException("Unsupported source file format " + format);
        SourceFile s = new SourceFile();
        s.world = Json.str(m, "world");
        s.source = Json.str(m, "source");
        s.kind = Json.str(m, "kind");
        Map<String, Object> game = Json.obj(m, "game");
        if (game != null) {
            s.gameId = Json.str(game, "id");
            s.gameBuild = Json.str(game, "build");
            s.gameRevision = Json.str(game, "revision");
            s.gamePatchline = Json.str(game, "patchline");
        }
        s.worldBackup = Json.str(m, "worldBackup");
        for (Object o : Json.list(m, "mods")) {
            Map<String, Object> e = (Map<String, Object>) o;
            s.mods.add(new BootCheck.Mod(Json.str(e, "id"), Json.str(e, "version"), Json.str(e, "sha256")));
        }
        Map<String, Object> settings = Json.obj(m, "settings");
        if (settings != null) s.settings = settings;
        Map<String, Object> from = Json.obj(m, "settingsFrom");
        if (from != null) s.settingsFrom = from;
        s.lastSnapshot = Json.str(m, "lastSnapshot");
        s.lastSeen = Json.str(m, "lastSeen");
        s.running = Json.bool(m, "running", false);
        s.builtInBackups = Json.obj(m, "builtInBackups");
        s.sourceGone = Json.str(m, "sourceGone");
        s.pendingRestore = Json.str(m, "pendingRestore");
        return s;
    }

    /**
     * Marks every source whose folder no longer exists as gone (today's date), leaving its snapshots alone.
     * Runs at startup and before every prune. Returns the worlds newly marked.
     */
    public static List<String> markGone(StoreLock lock, Store store, java.time.LocalDate today) throws java.io.IOException {
        if (lock == null) throw new IllegalArgumentException("Source files are written under the store lock");
        List<String> marked = new ArrayList<>();
        for (SourceFile s : store.sources()) {
            if (s.gone() || s.folderExists()) continue;
            s.sourceGone = today.toString();
            s.running = false;
            store.writeSource(s);
            store.log("source gone: " + s.world + " (" + s.source + ")");
            marked.add(s.world);
        }
        return marked;
    }
}
