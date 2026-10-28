package worldbackup.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.channels.FileChannel;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The backup store: one per machine by default, shared by every server and save.
 *
 * <pre>
 * &lt;store&gt;/
 *   store.json                    the store's format, and its machine-wide settings: the space cap
 *   sources/&lt;world&gt;.json          what each server or save tells the others about its world (SourceFile)
 *   lock                          held for every write (StoreLock)
 *   blocks/&lt;aa&gt;/&lt;sha256&gt;          64 KB blocks of file contents, named by hash
 *   blocks/tmp/                   blocks being written
 *   snapshots/&lt;world&gt;/&lt;id&gt;.json   one manifest per snapshot; a snapshot exists iff its manifest does
 *   actions.log                   every command and its result
 * </pre>
 *
 * Temp files carry a {@code .tmp} suffix, never a prefix, since a world's name may begin with anything. Every
 * temp file is flushed to disk before it's renamed into place, so a power cut leaves either the old file or
 * the whole new one. A manifest that can't be read is skipped and reported ({@link Scan}), never fatal.
 *
 * Every write here happens under {@link StoreLock}, which refuses a store written by a newer WorldBackup
 * (its {@code format} above {@link #FORMAT}): such a store is only read. The per-server files (last build,
 * last mods, a staged restore) live in each server's own folder, not here.
 */
public final class Store {
    public static final int BLOCK_SIZE = 64 * 1024;
    /** The store format this code writes; a store with a higher one is read only. */
    public static final long FORMAT = 1;

    private final Path root;

    public Store(Path root) {
        this.root = root;
    }

    public Path root() {
        return root;
    }

    public Path blocksDir() {
        return root.resolve("blocks");
    }

    public Path snapshotsDir() {
        return root.resolve("snapshots");
    }

    public Path worldSnapshots(String world) {
        return snapshotsDir().resolve(SnapshotNames.folder(world));
    }

    public Path sourcesDir() {
        return root.resolve("sources");
    }

    public Path sourcePath(String world) {
        return sourcesDir().resolve(SnapshotNames.folder(world) + ".json");
    }

    public Path blockPath(String hash) {
        return blocksDir().resolve(hash.substring(0, 2)).resolve(hash);
    }

    // ---- store.json ----------------------------------------------------------------------------------------

    public StoreSettings settings() throws IOException {
        Path f = root.resolve("store.json");
        if (!Files.exists(f)) return new StoreSettings();
        return StoreSettings.fromJson(Json.parseObject(Files.readString(f, StandardCharsets.UTF_8)));
    }

    public void saveSettings(StoreSettings s) throws IOException {
        writeAtomic(root.resolve("store.json"), Json.write(s.toJson()));
    }

    /** The format {@code store.json} records; {@link #FORMAT} for a new store. */
    public long format() throws IOException {
        Path f = root.resolve("store.json");
        if (!Files.exists(f)) return FORMAT;
        return Json.num(Json.parseObject(Files.readString(f, StandardCharsets.UTF_8)), "format", FORMAT);
    }

    /** True when a newer WorldBackup wrote this store, so this one may only read it. */
    public boolean readOnly() throws IOException {
        return format() > FORMAT;
    }

    // ---- sources -------------------------------------------------------------------------------------------

    public SourceFile source(String world) throws IOException {
        Path f = sourcePath(world);
        if (!Files.exists(f)) return null;
        return SourceFile.fromJson(Json.parseObject(Files.readString(f, StandardCharsets.UTF_8)));
    }

    /** Every world's source file, by name. Files that can't be read are skipped. */
    public List<SourceFile> sources() throws IOException {
        List<SourceFile> out = new ArrayList<>();
        if (!Files.isDirectory(sourcesDir())) return out;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(sourcesDir(), "*.json")) {
            for (Path f : ds) {
                try {
                    out.add(SourceFile.fromJson(Json.parseObject(Files.readString(f, StandardCharsets.UTF_8))));
                } catch (RuntimeException | IOException unreadable) {
                    // a newer format, or a half-edited file: not ours to fix
                }
            }
        }
        out.sort((a, b) -> a.world.compareToIgnoreCase(b.world));
        return out;
    }

    public void writeSource(SourceFile s) throws IOException {
        writeAtomic(sourcePath(s.world), Json.write(s.toJson()));
    }

    // ---- blocks --------------------------------------------------------------------------------------------

    public boolean hasBlock(String hash) {
        return Files.exists(blockPath(hash));
    }

    /** True when the block exists with the size it must have. A block of another size counts as missing. */
    public boolean hasBlock(String hash, long length) {
        try {
            return Files.size(blockPath(hash)) == length;
        } catch (IOException missing) {
            return false;
        }
    }

    /**
     * Stores {@code len} bytes of {@code buf} under {@code hash} unless a block with that hash and length
     * exists. A block of the wrong length (a write cut short) is replaced. True if written.
     */
    public boolean putBlock(String hash, byte[] buf, int len) throws IOException {
        Path target = blockPath(hash);
        if (hasBlock(hash, len)) return false;
        boolean replace = Files.exists(target);
        Path tmp = blocksDir().resolve("tmp");
        Files.createDirectories(tmp);
        Files.createDirectories(target.getParent());
        Path t = Files.createTempFile(tmp, hash.substring(0, 8), ".part");
        try {
            writeDurable(t, buf, len);
            try {
                if (replace) Files.move(t, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                else Files.move(t, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (FileAlreadyExistsException raced) {
                Files.deleteIfExists(t);
                return false;
            }
        } finally {
            Files.deleteIfExists(t);
        }
        return true;
    }

    public byte[] readBlock(String hash) throws IOException {
        return Files.readAllBytes(blockPath(hash));
    }

    public long blockSize(String hash) throws IOException {
        return Files.size(blockPath(hash));
    }

    /** Every stored block's hash and size. Leftover temp files are not blocks. */
    public Map<String, Long> allBlocks() throws IOException {
        Map<String, Long> out = new LinkedHashMap<>();
        Path dir = blocksDir();
        if (!Files.isDirectory(dir)) return out;
        try (DirectoryStream<Path> prefixes = Files.newDirectoryStream(dir)) {
            for (Path p : prefixes) {
                String name = p.getFileName().toString();
                if (name.length() != 2 || !Files.isDirectory(p)) continue;
                try (DirectoryStream<Path> blocks = Files.newDirectoryStream(p)) {
                    for (Path b : blocks) out.put(b.getFileName().toString(), Files.size(b));
                }
            }
        }
        return out;
    }

    public void deleteBlock(String hash) throws IOException {
        Files.deleteIfExists(blockPath(hash));
    }

    /** Removes block temp files left by a crash. Only safe under the store lock. */
    public void clearBlockTemp() throws IOException {
        Path tmp = blocksDir().resolve("tmp");
        if (!Files.isDirectory(tmp)) return;
        try (Stream<Path> s = Files.list(tmp)) {
            for (Path p : (Iterable<Path>) s::iterator) Files.deleteIfExists(p);
        }
    }

    // ---- manifests -----------------------------------------------------------------------------------------

    /** Every readable manifest, and what couldn't be read. Nothing here throws for one bad file. */
    public static final class Scan {
        /** Oldest first; snapshots taken in the same second are ordered by id. */
        public final List<Manifest> manifests = new ArrayList<>();
        /** "snapshots/World/x.json: reason", for each manifest that couldn't be read. */
        public final List<String> unreadable = new ArrayList<>();
        /** Temp manifests left by a crash ({@code *.json.tmp}, or the {@code _<id>.json} of 0.1.0). */
        public final List<Path> leftovers = new ArrayList<>();
    }

    public Scan scan() throws IOException {
        Scan out = new Scan();
        if (!Files.isDirectory(snapshotsDir())) return out;
        try (DirectoryStream<Path> worlds = Files.newDirectoryStream(snapshotsDir())) {
            for (Path w : worlds) {
                if (Files.isDirectory(w)) scanFolder(w, out);
            }
        }
        sort(out.manifests);
        return out;
    }

    private void scanFolder(Path dir, Scan out) throws IOException {
        try (DirectoryStream<Path> s = Files.newDirectoryStream(dir)) {
            for (Path p : s) {
                String name = p.getFileName().toString();
                if (name.endsWith(".json.tmp")) {
                    out.leftovers.add(p);
                    continue;
                }
                if (!name.endsWith(".json") || !Files.isRegularFile(p)) continue;
                String rel = "snapshots/" + dir.getFileName() + "/" + name;
                Manifest m;
                try {
                    m = Manifest.fromJson(Json.parseObject(Files.readString(p, StandardCharsets.UTF_8)));
                } catch (RuntimeException | IOException e) {
                    out.unreadable.add(rel + ": " + e.getMessage());
                    continue;
                }
                if (!name.equals(m.id + ".json")) {
                    // 0.1.0 wrote temp manifests as _<id>.json; a real one is always named after its id
                    if (name.equals("_" + m.id + ".json")) out.leftovers.add(p);
                    else out.unreadable.add(rel + ": its id is " + m.id);
                    continue;
                }
                if (!dir.getFileName().toString().equals(SnapshotNames.folder(m.world))) {
                    out.unreadable.add(rel + ": it belongs to world '" + m.world + "'");
                    continue;
                }
                out.manifests.add(m);
            }
        }
    }

    private static void sort(List<Manifest> ms) {
        ms.sort((a, b) -> {
            int c = Long.compare(a.createdMillis(), b.createdMillis());
            return c != 0 ? c : a.id.compareTo(b.id);
        });
    }

    public List<String> worlds() throws IOException {
        List<String> out = new ArrayList<>();
        if (!Files.isDirectory(snapshotsDir())) return out;
        for (Manifest m : allManifests()) {
            if (!out.contains(m.world)) out.add(m.world);
        }
        Collections.sort(out);
        return out;
    }

    /** A world's readable snapshots, oldest first. */
    public List<Manifest> manifests(String world) throws IOException {
        Scan out = new Scan();
        Path dir = worldSnapshots(world);
        if (Files.isDirectory(dir)) scanFolder(dir, out);
        out.manifests.removeIf(m -> !m.world.equals(world));
        sort(out.manifests);
        return out.manifests;
    }

    /** Every readable snapshot, oldest first. {@link #scan()} also tells what couldn't be read. */
    public List<Manifest> allManifests() throws IOException {
        return scan().manifests;
    }

    public Manifest find(String id) throws IOException {
        for (Manifest m : allManifests()) {
            if (m.id.equals(id)) return m;
        }
        return null;
    }

    public Path manifestPath(Manifest m) {
        return worldSnapshots(m.world).resolve(m.id + ".json");
    }

    /** Writes the manifest last, as {@code <id>.json.tmp} renamed to {@code <id>.json}: the snapshot exists from that moment. */
    public void writeManifest(Manifest m) throws IOException {
        if (!Manifest.plainName(m.id)) throw new IOException("Bad snapshot id " + m.id);
        Path target = manifestPath(m);
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(m.id + ".json.tmp");
        writeDurable(tmp, Json.write(m.toJson()).getBytes(StandardCharsets.UTF_8));
        move(tmp, target);
    }

    /** Rewrites a manifest that already exists (pin, unpin). */
    public void rewriteManifest(Manifest m) throws IOException {
        if (!Files.exists(manifestPath(m))) throw new IOException("Snapshot " + m.id + " no longer exists");
        writeManifest(m);
    }

    /** Deletes temp manifests left by a crash. Only safe under the store lock. */
    public void clearManifestTemp(Scan scan) throws IOException {
        for (Path p : scan.leftovers) Files.deleteIfExists(p);
    }

    public void deleteManifest(Manifest m) throws IOException {
        Files.deleteIfExists(manifestPath(m));
    }

    // ---- small files ---------------------------------------------------------------------------------------

    public static void writeAtomic(Path file, String text) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        writeDurable(tmp, text.getBytes(StandardCharsets.UTF_8));
        move(tmp, file);
    }

    static void writeDurable(Path file, byte[] data) throws IOException {
        writeDurable(file, data, data.length);
    }

    /** Writes the first {@code len} bytes of {@code data} and forces them to the disk before returning. */
    static void writeDurable(Path file, byte[] data, int len) throws IOException {
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            ByteBuffer b = ByteBuffer.wrap(data, 0, len);
            while (b.hasRemaining()) ch.write(b);
            ch.force(true);
        }
    }

    static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public void log(String line) throws IOException {
        Files.createDirectories(root);
        Files.writeString(root.resolve("actions.log"), line + System.lineSeparator(), StandardCharsets.UTF_8,
            java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
    }
}
