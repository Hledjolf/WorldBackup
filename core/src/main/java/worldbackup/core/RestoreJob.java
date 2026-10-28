package worldbackup.core;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * A staged restore (design/README.md, "Restoring"). {@code pending-restore.json} in the server's own data
 * folder records the world and the phase (one staged restore per server at a time), so a crash at any point is
 * finished at the next {@link #apply}:
 *
 * <ol>
 *   <li>{@code staged}: the snapshot is chosen and its blocks verified; a copy of its manifest is kept beside
 *       {@code pending-restore.json}, so the restore never depends on the store keeping it.</li>
 *   <li>{@code built}: the snapshot's files rebuilt under {@code <root>/.wb-restore/} (same drive, so moving them
 *       into place is a rename).</li>
 *   <li>{@code saved}: a {@code pre-restore} snapshot of the current world taken and verified, so the restore can
 *       be undone.</li>
 *   <li>{@code swapped}: each live file moved to {@code <root>/.wb-old/}, each rebuilt file moved into place.
 *       Resumable file by file.</li>
 *   <li>done: both folders deleted, {@code pending-restore.json} removed.</li>
 * </ol>
 *
 * <p>A restore puts back every file in the snapshot, and removes a current file only if both the snapshot's
 * selection and today's select it, so a snapshot taken without player files never deletes today's. WorldBackup's
 * own data folder is never touched. A failure before {@code saved} changes nothing; a failure during the swap
 * moves every file back from {@code .wb-old}. Either way the restore is marked {@code failed} with the reason
 * and stays recorded until it's cancelled or another is staged; it's never retried by itself.
 *
 * <p>Restore copies blocks into new files; it never links into the store.
 */
public final class RestoreJob {
    public static final String RESTORE_DIR = ".wb-restore";
    public static final String OLD_DIR = ".wb-old";

    public enum Phase { STAGED, BUILT, SAVED, SWAPPED, FAILED }

    private final Store store;
    private final SnapshotWriter writer;
    private final Path dataDir;
    private final Path pendingFile;
    private final Path manifestCopy;
    /** Tests only: throw after this many files have been swapped in, to simulate a failure. 0: never. */
    int crashAfterSwapping = 0;
    /** Tests only: when true, the failure above is a crash (left as is), not an error (rolled back). */
    boolean crashIsKill = true;

    /** {@code dataDir}: the server's or save's own folder, {@code mods/Hledjolf_WorldBackup/} in Hytale. */
    public RestoreJob(Store store, SnapshotWriter writer, Path dataDir) {
        this.store = store;
        this.writer = writer;
        this.dataDir = dataDir;
        this.pendingFile = dataDir.resolve("pending-restore.json");
        this.manifestCopy = dataDir.resolve("pending-restore-manifest.json");
    }

    public static final class Pending {
        public String world;
        public String snapshotId;
        public Phase phase;
        public String preRestoreId;
        /** Why it failed, when {@link #phase} is {@code FAILED}. */
        public String failure;

        public boolean failed() {
            return phase == Phase.FAILED;
        }

        Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("format", 1L);
            m.put("world", world);
            m.put("snapshot", snapshotId);
            m.put("phase", phase.name().toLowerCase());
            m.put("preRestore", preRestoreId);
            m.put("failure", failure);
            return m;
        }

        static Pending fromJson(Map<String, Object> m) {
            Pending p = new Pending();
            p.world = Json.str(m, "world");
            p.snapshotId = Json.str(m, "snapshot");
            p.phase = Phase.valueOf(Json.str(m, "phase").toUpperCase());
            p.preRestoreId = Json.str(m, "preRestore");
            p.failure = Json.str(m, "failure");
            return p;
        }
    }

    /** The server's staged (or failed) restore, whichever world it's for, or null. */
    public Pending pending() throws IOException {
        if (!Files.exists(pendingFile)) return null;
        return Pending.fromJson(Json.parseObject(Files.readString(pendingFile, StandardCharsets.UTF_8)));
    }

    /** The staged (or failed) restore of {@code world}, or null. */
    public Pending pending(String world) throws IOException {
        Pending p = pending();
        return p != null && world.equals(p.world) ? p : null;
    }

    /** The snapshot id this server must keep from every prune: its staged restore's, while not failed. */
    public String protectedId() throws IOException {
        Pending p = pending();
        return p == null || p.failed() ? null : p.snapshotId;
    }

    private void save(Pending p) throws IOException {
        Store.writeAtomic(pendingFile, Json.write(p.toJson()));
    }

    /**
     * Stages a restore of {@code snapshotId} for the next start. Refuses a snapshot from an older game build
     * than {@code currentBuild} unless {@code confirmOlder}, and a snapshot with missing or damaged blocks. A
     * failed restore is replaced; any other staged restore must be cancelled first.
     */
    public Pending stage(StoreLock lock, String snapshotId, String currentBuild, boolean confirmOlder) throws IOException {
        if (lock == null) throw new IllegalArgumentException("Staging runs under the store lock");
        Manifest m = store.find(snapshotId);
        if (m == null) throw new IOException("No snapshot " + snapshotId);
        if (!confirmOlder && currentBuild != null && m.gameBuild != null && Builds.compare(m.gameBuild, currentBuild) < 0) {
            throw new IOException("Snapshot " + snapshotId + " is from build " + m.gameBuild + ", older than the game's "
                + currentBuild + ". The game will upgrade it again when it loads. Confirm to restore it anyway.");
        }
        Pending other = pending();
        if (other != null && !other.failed() && (!other.world.equals(m.world) || other.phase != Phase.STAGED)) {
            throw new IOException("A restore of " + other.snapshotId + " is already staged; cancel it first");
        }
        String bad = Verify.firstProblem(store, m);
        if (bad != null) throw new IOException("Snapshot " + snapshotId + " can't be restored: " + bad);
        Store.writeAtomic(manifestCopy, Json.write(m.toJson()));
        Pending p = new Pending();
        p.world = m.world;
        p.snapshotId = m.id;
        p.phase = Phase.STAGED;
        save(p);
        store.log("restore staged " + m.id);
        return p;
    }

    /** Clears a staged or failed restore. Refused only mid-swap (after a crash); the next start resolves that. */
    public boolean cancel(StoreLock lock, Path root, String world) throws IOException {
        Pending p = pending(world);
        if (p == null) return false;
        if (p.phase == Phase.SWAPPED) throw new IOException("The restore of " + p.snapshotId + " is half applied; it finishes at the next start");
        deleteTree(root.resolve(RESTORE_DIR));
        Files.deleteIfExists(pendingFile);
        Files.deleteIfExists(manifestCopy);
        store.log("restore " + (p.failed() ? "failure cleared " : "cancelled ") + p.snapshotId);
        return true;
    }

    /**
     * Applies the world's staged restore, if any, from whatever phase it reached. Call before the game opens
     * the world. {@code preRestore} describes the current world for its {@code pre-restore} snapshot (root,
     * today's selection, world, game build); its trigger is set here. Returns the restore with its phase: null
     * when nothing was staged, {@code FAILED} (with {@link Pending#failure}) when it failed and the world was
     * left or put back as it was, otherwise the finished restore. Throws only when even putting the world back
     * failed.
     */
    public Pending apply(StoreLock lock, Path root, SnapshotWriter.Request preRestore) throws IOException {
        if (lock == null) throw new IllegalArgumentException("A restore runs under the store lock");
        Pending p = pending(preRestore.world);
        if (p == null || p.failed()) return p;
        Selection today = withoutDataDir(root, preRestore.selection);
        preRestore.selection = today;
        Manifest m = staged(p);
        if (m == null) {
            if (p.phase == Phase.SWAPPED) throw new IOException("The restore of " + p.snapshotId + " is half applied and its manifest is lost");
            return failed(root, p, "the snapshot no longer exists");
        }
        List<Manifest.FileEntry> files = filesToRestore(root, m);
        Selection then = m.selection != null ? withoutDataDir(root, m.selection) : today;

        if (p.phase == Phase.STAGED || p.phase == Phase.BUILT || p.phase == Phase.SAVED) {
            try {
                if (p.phase == Phase.STAGED) {
                    build(root, files);
                    p.phase = Phase.BUILT;
                    save(p);
                }
                if (p.phase == Phase.BUILT) {
                    preRestore.trigger = Trigger.PRE_RESTORE;
                    preRestore.rehashAll = false;
                    Manifest pre = writer.write(lock, preRestore).manifest;
                    String bad = Verify.firstProblem(store, pre);
                    if (bad != null) throw new IOException("the undo snapshot " + pre.id + " didn't verify: " + bad);
                    p.preRestoreId = pre.id;
                    p.phase = Phase.SAVED;
                    save(p);
                }
            } catch (IOException | RuntimeException e) {
                return failed(root, p, e.getMessage());
            }
            p.phase = Phase.SWAPPED; // recorded first: from here on, the world is either finished or put back
            save(p);
        }
        try {
            swap(root, files, today, then);
        } catch (IOException | RuntimeException e) {
            if (e instanceof SimulatedKill) throw (SimulatedKill) e;
            try {
                rollBack(root, files);
            } catch (IOException | RuntimeException again) {
                p.failure = "half applied, and putting the world back failed too: " + again.getMessage()
                    + ". Files are in " + RESTORE_DIR + " and " + OLD_DIR + "; restore " + p.preRestoreId + " to undo.";
                save(p);
                store.log("restore of " + p.snapshotId + " FAILED mid-swap: " + e.getMessage() + "; roll back failed: " + again.getMessage());
                throw new IOException("The restore of " + p.snapshotId + " " + p.failure, again);
            }
            return failed(root, p, e.getMessage() + " (the world was put back as it was)");
        }
        deleteTree(root.resolve(OLD_DIR));
        deleteTree(root.resolve(RESTORE_DIR));
        Files.deleteIfExists(pendingFile);
        Files.deleteIfExists(manifestCopy);
        store.log("restore applied " + m.id + " (undo: " + p.preRestoreId + ")");
        return p;
    }

    private Pending failed(Path root, Pending p, String reason) throws IOException {
        deleteTree(root.resolve(RESTORE_DIR));
        p.phase = Phase.FAILED;
        p.failure = reason;
        save(p);
        store.log("restore of " + p.snapshotId + " failed: " + reason);
        return p;
    }

    /** The staged snapshot: from the store, or the copy kept when it was staged. */
    private Manifest staged(Pending p) throws IOException {
        Manifest m = store.find(p.snapshotId);
        if (m != null) return m;
        if (!Files.exists(manifestCopy)) return null;
        try {
            Manifest copy = Manifest.fromJson(Json.parseObject(Files.readString(manifestCopy, StandardCharsets.UTF_8)));
            return copy.id.equals(p.snapshotId) ? copy : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The snapshot's files, minus anything under this server's data folder. */
    private List<Manifest.FileEntry> filesToRestore(Path root, Manifest m) {
        String data = dataPrefix(root);
        List<Manifest.FileEntry> out = new ArrayList<>();
        for (Manifest.FileEntry f : m.files) {
            if (data == null || !f.path.toLowerCase().startsWith(data.toLowerCase())) out.add(f);
        }
        return out;
    }

    /** Rebuilds each file under .wb-restore, checking each block's hash. Skips files already complete. */
    private void build(Path root, List<Manifest.FileEntry> files) throws IOException {
        Path base = root.resolve(RESTORE_DIR);
        MessageDigest md = FileListing.newDigest();
        for (Manifest.FileEntry f : files) {
            Path out = inside(base, f.path);
            if (Files.exists(out) && Files.size(out) == f.size && Files.getLastModifiedTime(out).toMillis() == f.mtimeMillis) continue;
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
    }

    private void swap(Path root, List<Manifest.FileEntry> files, Selection today, Selection then) throws IOException {
        Path restore = root.resolve(RESTORE_DIR);
        Path old = root.resolve(OLD_DIR);
        Set<String> inSnapshot = new HashSet<>();
        for (Manifest.FileEntry f : files) {
            inSnapshot.add(f.path.toLowerCase());
            Path rebuilt = inside(restore, f.path);
            if (!Files.exists(rebuilt)) continue; // already moved in
            Path live = inside(root, f.path);
            Path kept = inside(old, f.path);
            if (Files.exists(live)) {
                if (Files.exists(kept)) Files.delete(live); // a resumed swap: .wb-old already has the original
                else moveInto(live, kept);
            }
            moveInto(rebuilt, live);
            if (crashAfterSwapping > 0 && --crashAfterSwapping == 0) {
                if (crashIsKill) throw new SimulatedKill();
                throw new IOException("simulated failure");
            }
        }
        // files created since the snapshot (new regions, new players) leave the world too, if the snapshot
        // would have taken them: what it never covered isn't its to remove
        for (String rel : SnapshotWriter.listSelected(root, today, null)) {
            if (!inSnapshot.contains(rel.toLowerCase()) && then.selects(rel)) moveInto(inside(root, rel), inside(old, rel));
        }
    }

    /**
     * Puts the world back as it was before the swap: every rebuilt file that was moved in goes back to
     * .wb-restore, and every file in .wb-old goes back to its place.
     */
    private void rollBack(Path root, List<Manifest.FileEntry> files) throws IOException {
        Path restore = root.resolve(RESTORE_DIR);
        Path old = root.resolve(OLD_DIR);
        for (Manifest.FileEntry f : files) {
            if (Files.exists(inside(restore, f.path))) continue; // never moved in
            Path live = inside(root, f.path);
            if (Files.exists(live)) moveInto(live, inside(restore, f.path));
        }
        if (Files.isDirectory(old)) {
            List<Path> kept = new ArrayList<>();
            try (Stream<Path> walk = Files.walk(old)) {
                walk.filter(Files::isRegularFile).forEach(kept::add);
            }
            for (Path k : kept) {
                String rel = old.relativize(k).toString().replace('\\', '/');
                Path live = inside(root, rel);
                if (Files.exists(live)) Files.delete(live);
                moveInto(k, live);
            }
        }
        deleteTree(old);
    }

    /** {@code rel} under {@code base}, refusing any path that would end up outside it. */
    static Path inside(Path base, String rel) throws IOException {
        if (!Manifest.safePath(rel)) throw new IOException("Refusing path " + rel);
        Path b = base.toAbsolutePath().normalize();
        Path p = b.resolve(rel).normalize();
        if (!p.startsWith(b) || p.equals(b)) throw new IOException("Refusing path " + rel);
        return p;
    }

    /** This server's data folder relative to {@code root}, '/'-separated with a trailing '/', or null when outside it. */
    private String dataPrefix(Path root) {
        Path r = root.toAbsolutePath().normalize(), d = dataDir.toAbsolutePath().normalize();
        if (!d.startsWith(r) || d.equals(r)) return null;
        return r.relativize(d).toString().replace('\\', '/') + "/";
    }

    /**
     * {@code sel} plus an exclude of this server's data folder when it's inside the world's root (Hytale:
     * {@code mods/Hledjolf_WorldBackup/}), so a restore never swaps out its own {@code pending-restore.json}.
     */
    private Selection withoutDataDir(Path root, Selection sel) {
        String data = dataPrefix(root);
        if (data == null) return sel;
        Selection out = new Selection();
        out.include.addAll(sel.include);
        out.exclude.addAll(sel.exclude);
        out.volatiles.addAll(sel.volatiles);
        out.exclude.add(data + "**");
        return out;
    }

    private static void moveInto(Path from, Path to) throws IOException {
        Files.createDirectories(to.getParent());
        Store.move(from, to);
    }

    static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) walk.sorted(Comparator.reverseOrder())::iterator) Files.deleteIfExists(p);
        }
    }

    /** Tests only: a crash in the middle of the swap, which leaves the files as they are. */
    static final class SimulatedKill extends RuntimeException {
        SimulatedKill() {
            super("simulated crash");
        }
    }
}
