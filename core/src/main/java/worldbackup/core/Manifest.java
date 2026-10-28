package worldbackup.core;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One snapshot: one world at one moment. Plain JSON, so a person can rebuild a file without the mod by
 * concatenating its blocks ({@code blocks/<aa>/<hash>}) in order.
 */
public final class Manifest {
    public static final long FORMAT = 1;

    public static final class FileEntry {
        public final String path; // relative to the world's root, '/'-separated
        public final long size;
        public final long mtimeMillis;
        public final List<String> blocks; // SHA-256 of each 64 KB block, in order
        /** SHA-256 of the whole file, or null in manifests written before 1.0. */
        public final String sha256;

        public FileEntry(String path, long size, long mtimeMillis, List<String> blocks) {
            this(path, size, mtimeMillis, blocks, null);
        }

        public FileEntry(String path, long size, long mtimeMillis, List<String> blocks, String sha256) {
            this.path = path;
            this.size = size;
            this.mtimeMillis = mtimeMillis;
            this.blocks = blocks;
            this.sha256 = sha256;
        }

        /** The size block {@code i} must have: a full block, or what's left of the file for the last one. */
        public long blockLength(int i) {
            return i < blocks.size() - 1 ? Store.BLOCK_SIZE : size - (long) Store.BLOCK_SIZE * (blocks.size() - 1);
        }
    }

    public String id;
    public String world;
    /** The world's root folder when it was taken; tells two sources with the same name apart. */
    public String source;
    public Trigger trigger;
    public String label;
    public boolean pinned;
    public String created; // ISO-8601 with offset, local time
    public String by;
    public String gameId;
    public String gameBuild;
    public String gameRevision;
    public String profile;
    /**
     * The include and exclude globs the snapshot was taken with, or null in manifests written before 1.0. A
     * restore removes a current file only if both this selection and today's select it.
     */
    public Selection selection;
    public boolean consistent = true;
    /** Bytes of blocks this snapshot added to the store when it was taken: its churn, for the budget check. */
    public long addedBytes;
    public long tookMillis;
    public List<String> changedDuringCopy = new ArrayList<>();
    public List<FileEntry> files = new ArrayList<>();
    /** Game- or adapter-specific extras (a mod list, a mod-change diff), kept as given. */
    public Map<String, Object> extras = new LinkedHashMap<>();

    public long createdMillis() {
        return OffsetDateTime.parse(created).toInstant().toEpochMilli();
    }

    /** The calendar day it was taken on, where it was taken. */
    public java.time.LocalDate localDate() {
        return OffsetDateTime.parse(created).toLocalDate();
    }

    public long totalBytes() {
        long t = 0;
        for (FileEntry f : files) t += f.size;
        return t;
    }

    public FileEntry file(String path) {
        for (FileEntry f : files) {
            if (f.path.equals(path)) return f;
        }
        return null;
    }

    public Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("format", FORMAT);
        m.put("id", id);
        m.put("world", world);
        m.put("source", source);
        m.put("trigger", trigger.id);
        m.put("label", label);
        m.put("pinned", pinned);
        m.put("created", created);
        m.put("by", by);
        Map<String, Object> game = new LinkedHashMap<>();
        game.put("id", gameId);
        game.put("build", gameBuild);
        game.put("revision", gameRevision);
        m.put("game", game);
        m.put("profile", profile);
        if (selection != null) {
            Map<String, Object> sel = new LinkedHashMap<>();
            sel.put("include", new ArrayList<Object>(selection.include));
            sel.put("exclude", new ArrayList<Object>(selection.exclude));
            m.put("selection", sel);
        }
        m.put("consistent", consistent);
        m.put("addedBytes", addedBytes);
        m.put("tookMillis", tookMillis);
        m.put("changedDuringCopy", new ArrayList<Object>(changedDuringCopy));
        m.put("blockSize", (long) Store.BLOCK_SIZE);
        List<Object> fl = new ArrayList<>();
        for (FileEntry f : files) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("path", f.path);
            e.put("size", f.size);
            e.put("mtime", f.mtimeMillis);
            e.put("blocks", new ArrayList<Object>(f.blocks));
            if (f.sha256 != null) e.put("sha256", f.sha256);
            fl.add(e);
        }
        m.put("files", fl);
        m.put("extras", extras);
        return m;
    }

    /**
     * Reads a manifest, refusing anything a restore or export could be misled by: an unknown format or
     * trigger, an id that isn't a plain file name, a file path that could leave the world's root, or a block
     * name that isn't a SHA-256 (design/README.md, "The store").
     */
    @SuppressWarnings("unchecked")
    public static Manifest fromJson(Map<String, Object> m) {
        long format = Json.num(m, "format", -1);
        if (format != FORMAT) throw new IllegalArgumentException("Unsupported manifest format " + format);
        Manifest x = new Manifest();
        x.id = Json.str(m, "id");
        if (!plainName(x.id)) throw new IllegalArgumentException("Bad snapshot id " + x.id);
        x.world = Json.str(m, "world");
        if (x.world == null || x.world.isEmpty()) throw new IllegalArgumentException("No world in " + x.id);
        x.source = Json.str(m, "source");
        x.trigger = Trigger.of(Json.str(m, "trigger"));
        x.label = Json.str(m, "label");
        x.pinned = Json.bool(m, "pinned", false);
        x.created = Json.str(m, "created");
        try {
            OffsetDateTime.parse(x.created);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Bad time in " + x.id + ": " + x.created);
        }
        x.by = Json.str(m, "by");
        Map<String, Object> game = Json.obj(m, "game");
        if (game != null) {
            x.gameId = Json.str(game, "id");
            x.gameBuild = Json.str(game, "build");
            x.gameRevision = Json.str(game, "revision");
        }
        x.profile = Json.str(m, "profile");
        Map<String, Object> sel = Json.obj(m, "selection");
        if (sel != null) {
            x.selection = new Selection();
            for (Object o : Json.list(sel, "include")) x.selection.include.add(o.toString());
            for (Object o : Json.list(sel, "exclude")) x.selection.exclude.add(o.toString());
        }
        x.consistent = Json.bool(m, "consistent", true);
        x.addedBytes = Json.num(m, "addedBytes", 0);
        x.tookMillis = Json.num(m, "tookMillis", 0);
        for (Object o : Json.list(m, "changedDuringCopy")) x.changedDuringCopy.add(o.toString());
        for (Object o : Json.list(m, "files")) {
            Map<String, Object> e = (Map<String, Object>) o;
            String path = Json.str(e, "path");
            if (!safePath(path)) throw new IllegalArgumentException("Bad file path in " + x.id + ": " + path);
            List<String> blocks = new ArrayList<>();
            for (Object b : Json.list(e, "blocks")) {
                if (!isHash(b.toString())) throw new IllegalArgumentException("Bad block name in " + x.id + ": " + b);
                blocks.add(b.toString());
            }
            String sha = Json.str(e, "sha256");
            if (sha != null && !isHash(sha)) throw new IllegalArgumentException("Bad sha256 in " + x.id + ": " + sha);
            long size = Json.num(e, "size", 0);
            long full = (long) Store.BLOCK_SIZE * blocks.size();
            if (size < 0 || size > full || (blocks.size() > 0 && size <= full - Store.BLOCK_SIZE) || (blocks.isEmpty() && size != 0))
                throw new IllegalArgumentException("Size and blocks of " + path + " in " + x.id + " don't agree");
            x.files.add(new FileEntry(path, size, Json.num(e, "mtime", 0), blocks, sha));
        }
        Map<String, Object> extras = Json.obj(m, "extras");
        if (extras != null) x.extras = extras;
        return x;
    }

    /** A name usable as one file name: no separators, no "." or "..", nothing Windows refuses. */
    public static boolean plainName(String id) {
        if (id == null || id.isEmpty() || id.equals(".") || id.equals("..") || id.length() > 240) return false;
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            if (c < 0x20 || "<>:\"/\\|?*".indexOf(c) >= 0) return false;
        }
        return !id.endsWith(".") && !id.endsWith(" ");
    }

    /**
     * A path that stays inside the world's root: relative, '/'-separated, no empty, "." or ".." segment, no
     * drive letter or other colon, no backslash, no control characters.
     */
    public static boolean safePath(String path) {
        if (path == null || path.isEmpty() || path.startsWith("/") || path.indexOf('\\') >= 0 || path.indexOf(':') >= 0) return false;
        for (String seg : path.split("/", -1)) {
            if (seg.isEmpty() || seg.equals(".") || seg.equals("..")) return false;
            for (int i = 0; i < seg.length(); i++) {
                if (seg.charAt(i) < 0x20) return false;
            }
        }
        return true;
    }

    static boolean isHash(String s) {
        if (s == null || s.length() != 64) return false;
        for (int i = 0; i < 64; i++) {
            char c = s.charAt(i);
            if (!(c >= '0' && c <= '9') && !(c >= 'a' && c <= 'f')) return false;
        }
        return true;
    }
}
