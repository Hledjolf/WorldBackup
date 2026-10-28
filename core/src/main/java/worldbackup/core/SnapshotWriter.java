package worldbackup.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Takes a snapshot of one world's root into the store: each selected file is read in 64 KB blocks, each block
 * stored once by hash, and the manifest written last (design/README.md, "The store").
 *
 * <p>A file whose size and modification time match the previous snapshot's reuses that block list without
 * being read, unless the request says {@code rehashAll} (pre-update snapshots and verify), the previous
 * snapshot listed it as changed during its copy, or any of its blocks is missing or the wrong size. A file
 * that changes while it's copied is copied again: once for ordinary files, after which the snapshot is marked
 * inconsistent; up to {@link #VOLATILE_TRIES} times for volatile files, which never make a snapshot
 * inconsistent. A copy that never came out stable is recorded with modification time 0, so it's never reused.
 */
public final class SnapshotWriter {
    public static final int VOLATILE_TRIES = 5;

    public static final class Request {
        public Path root;
        public Selection selection = Selection.everything();
        public String world;
        public Trigger trigger;
        public String label;
        public boolean pinned;
        public String by = "console";
        public String gameId;
        public String gameBuild;
        public String gameRevision;
        public String profile;
        public boolean rehashAll;
        /** Read speed limit in bytes per second ({@code MaxCopyMBps}); 0 means none. Snapshots in setup() pass 0. */
        public long maxBytesPerSecond;
        public Map<String, Object> extras = new LinkedHashMap<>();
    }

    public static final class Result {
        public Manifest manifest;
        public long bytesRead;
        public int filesReused;
    }

    private final Store store;
    private final Clock clock;
    /** Tests only: runs after each file copy, before the file is checked again. */
    java.util.function.Consumer<String> afterCopy = rel -> { };

    public SnapshotWriter(Store store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    /** Writes the snapshot. The caller holds {@code lock}, so a prune can't remove blocks this snapshot reuses. */
    public Result write(StoreLock lock, Request r) throws IOException {
        if (lock == null) throw new IllegalArgumentException("A snapshot is written under the store lock");
        long t0 = System.nanoTime();
        Manifest prev = r.rehashAll ? null : latest(r.world);
        Manifest m = new Manifest();
        OffsetDateTime now = OffsetDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
        m.id = uniqueId(r.world, now.toLocalDateTime(), r.trigger);
        m.world = r.world;
        m.source = r.root.toAbsolutePath().normalize().toString();
        m.trigger = r.trigger;
        m.label = r.label;
        m.pinned = r.pinned || r.trigger == Trigger.MILESTONE;
        m.created = now.toString();
        m.by = r.by;
        m.gameId = r.gameId;
        m.gameBuild = r.gameBuild;
        m.gameRevision = r.gameRevision;
        m.profile = r.profile;
        m.selection = new Selection();
        m.selection.include.addAll(r.selection.include);
        m.selection.exclude.addAll(r.selection.exclude);
        m.extras = r.extras;

        Result res = new Result();
        res.manifest = m;
        long added = 0;
        Throttle throttle = new Throttle(r.maxBytesPerSecond);
        for (String rel : listSelected(r.root, r.selection, storeInside(r.root))) {
            Path file = r.root.resolve(rel);
            Manifest.FileEntry reuse = prev == null ? null : prev.file(rel);
            Stat before = Stat.of(file);
            if (before == null) continue; // deleted since listing
            if (reuse != null && reuse.mtimeMillis != 0 && reuse.size == before.size && reuse.mtimeMillis == before.mtime
                && !prev.changedDuringCopy.contains(rel) && allBlocksPresent(reuse)) {
                m.files.add(reuse);
                res.filesReused++;
                continue;
            }
            boolean vol = r.selection.isVolatile(rel);
            int tries = vol ? VOLATILE_TRIES : 2;
            Copied c = null;
            boolean stable = false;
            for (int t = 0; t < tries; t++) {
                c = copy(file, throttle);
                if (c == null) break; // deleted while copying
                res.bytesRead += c.size;
                added += c.addedBytes;
                afterCopy.accept(rel);
                Stat after = Stat.of(file);
                if (after != null && after.size == before.size && after.mtime == before.mtime && c.size == before.size) {
                    stable = true;
                    break;
                }
                if (after == null) break;
                before = after;
            }
            if (c == null) continue;
            if (!stable && !vol) {
                m.consistent = false;
                m.changedDuringCopy.add(rel);
            }
            m.files.add(new Manifest.FileEntry(rel, c.size, stable ? before.mtime : 0, c.blocks, c.sha256));
        }
        m.addedBytes = added;
        m.tookMillis = (System.nanoTime() - t0) / 1_000_000;
        store.writeManifest(m);
        return res;
    }

    /**
     * True when a non-volatile selected file was added, removed, or changed size or modification time since
     * {@code prev}: what {@code SkipUnchanged} asks. No file is read.
     */
    public static boolean changedSince(Path root, Selection sel, Manifest prev) throws IOException {
        if (prev == null) return true;
        List<String> now = listSelected(root, sel, null);
        int counted = 0;
        for (String rel : now) {
            if (sel.isVolatile(rel)) continue;
            counted++;
            Manifest.FileEntry e = prev.file(rel);
            Stat st = Stat.of(root.resolve(rel));
            if (e == null || st == null || e.size != st.size || e.mtimeMillis != st.mtime) return true;
        }
        int before = 0;
        for (Manifest.FileEntry e : prev.files) {
            if (!sel.isVolatile(e.path)) before++;
        }
        return before != counted;
    }

    public Manifest latest(String world) throws IOException {
        List<Manifest> all = store.manifests(world);
        return all.isEmpty() ? null : all.get(all.size() - 1);
    }

    private String uniqueId(String world, LocalDateTime when, Trigger trigger) throws IOException {
        String base = SnapshotNames.id(world, when, trigger);
        String id = base;
        for (int n = 2; Files.exists(store.worldSnapshots(world).resolve(id + ".json")); n++) id = base + "-" + n;
        return id;
    }

    private boolean allBlocksPresent(Manifest.FileEntry e) {
        for (int i = 0; i < e.blocks.size(); i++) {
            if (!store.hasBlock(e.blocks.get(i), e.blockLength(i))) return false;
        }
        return true;
    }

    /** The store's path relative to {@code root} plus '/', when the store is inside the world's root; otherwise null. */
    private String storeInside(Path root) {
        Path s = store.root().toAbsolutePath().normalize();
        Path w = root.toAbsolutePath().normalize();
        return s.startsWith(w) && !s.equals(w) ? w.relativize(s).toString().replace('\\', '/') + "/" : null;
    }

    /**
     * Every selected regular file under {@code root}, '/'-separated and sorted, skipping {@code skipPrefix} (the
     * store). A folder that disappears or can't be read during the walk is skipped, not fatal. A name no
     * manifest may hold (a colon or backslash, possible on Linux) is skipped.
     */
    static List<String> listSelected(Path root, Selection sel, String skipPrefix) throws IOException {
        List<String> out = new ArrayList<>();
        if (!Files.isDirectory(root)) return out;
        Files.walkFileTree(root, new java.nio.file.SimpleFileVisitor<Path>() {
            @Override
            public java.nio.file.FileVisitResult visitFile(Path p, java.nio.file.attribute.BasicFileAttributes a) {
                if (!a.isRegularFile()) return java.nio.file.FileVisitResult.CONTINUE;
                String rel = root.relativize(p).toString().replace('\\', '/');
                if (skipPrefix != null && rel.startsWith(skipPrefix)) return java.nio.file.FileVisitResult.CONTINUE;
                if (Manifest.safePath(rel) && sel.selects(rel)) out.add(rel);
                return java.nio.file.FileVisitResult.CONTINUE;
            }

            @Override
            public java.nio.file.FileVisitResult visitFileFailed(Path p, IOException e) {
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
        out.sort(null);
        return out;
    }

    private static final class Copied {
        long size;
        long addedBytes;
        String sha256;
        List<String> blocks = new ArrayList<>();
    }

    /** Paces reading to a byte rate by sleeping when ahead of it. */
    static final class Throttle {
        private final long bytesPerSecond;
        private final long t0 = System.nanoTime();
        private long bytes;

        Throttle(long bytesPerSecond) {
            this.bytesPerSecond = bytesPerSecond;
        }

        void took(int n) throws IOException {
            if (bytesPerSecond <= 0) return;
            bytes += n;
            long dueNanos = bytes * 1_000_000_000L / bytesPerSecond;
            long aheadMillis = (dueNanos - (System.nanoTime() - t0)) / 1_000_000;
            if (aheadMillis <= 0) return;
            try {
                Thread.sleep(aheadMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new java.io.InterruptedIOException("Snapshot interrupted");
            }
        }
    }

    private Copied copy(Path file, Throttle throttle) throws IOException {
        Copied c = new Copied();
        byte[] buf = new byte[Store.BLOCK_SIZE];
        MessageDigest md = FileListing.newDigest();
        MessageDigest whole = FileListing.newDigest();
        try (InputStream in = Files.newInputStream(file)) {
            while (true) {
                int n = in.readNBytes(buf, 0, buf.length);
                if (n <= 0) break;
                whole.update(buf, 0, n);
                md.reset();
                md.update(buf, 0, n);
                String hash = FileListing.hex(md.digest());
                if (store.putBlock(hash, buf, n)) c.addedBytes += n;
                c.blocks.add(hash);
                c.size += n;
                throttle.took(n);
                if (n < buf.length) break;
            }
        } catch (NoSuchFileException gone) {
            return null;
        }
        c.sha256 = FileListing.hex(whole.digest());
        return c;
    }

    static final class Stat {
        final long size;
        final long mtime;

        private Stat(long size, long mtime) {
            this.size = size;
            this.mtime = mtime;
        }

        static Stat of(Path f) {
            try {
                return new Stat(Files.size(f), Files.getLastModifiedTime(f).toMillis());
            } catch (IOException e) {
                return null;
            }
        }
    }
}
