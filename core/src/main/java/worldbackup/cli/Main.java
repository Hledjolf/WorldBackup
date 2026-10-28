package worldbackup.cli;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Stream;
import worldbackup.app.Backups;
import worldbackup.app.Profiles;
import worldbackup.app.Reports;
import worldbackup.app.Settings;
import worldbackup.core.BootCheck;
import worldbackup.core.Budget;
import worldbackup.core.Manifest;
import worldbackup.core.RestoreJob;
import worldbackup.core.SnapshotWriter;
import worldbackup.core.Store;
import worldbackup.core.StoreLock;
import worldbackup.core.Verify;

/**
 * The command line: {@code java -jar WorldBackup-<ver>.jar <command>}, with no game running
 * (design/README.md, decision 1). Reads any store; restores into a stopped server or save.
 */
public final class Main {
    private Main() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    static int run(String[] argv, PrintStream out, PrintStream err) {
        List<String> args = new ArrayList<>(List.of(argv));
        Path storeDir = Backups.defaultStoreDir();
        int at = args.indexOf("--store");
        if (at >= 0) {
            if (at + 1 >= args.size() || args.get(at + 1).startsWith("--")) {
                err.println("worldbackup: --store needs a folder");
                return 2;
            }
            storeDir = Path.of(args.get(at + 1));
            args.remove(at + 1);
            args.remove(at);
        }
        boolean intoOther = args.remove("--into-other");
        if (args.isEmpty() || args.get(0).equals("help") || args.get(0).equals("--help")) {
            usage(out);
            return args.isEmpty() ? 2 : 0;
        }
        Store store = new Store(storeDir);
        try {
            switch (args.get(0)) {
                case "worlds":
                    Reports.worlds(store, new HashSet<>()).forEach(out::println);
                    return 0;
                case "list":
                    if (args.size() < 2) {
                        Reports.storeWorlds(store).forEach(out::println);
                        return 0;
                    }
                    Reports.list(store, String.join(" ", args.subList(1, args.size()))).forEach(out::println);
                    return 0;
                case "budget":
                    Reports.budget(Budget.check(Reports.budgetInput(store, new Settings(storeDir.resolve(".none")).server(), -1)), null).forEach(out::println);
                    return 0;
                case "verify": {
                    Manifest m = find(store, args.get(1));
                    String problem = Verify.firstProblem(store, m);
                    out.println(problem == null ? m.id + " is whole." : m.id + " is damaged: " + problem);
                    return problem == null ? 0 : 1;
                }
                case "export": {
                    if (args.size() < 3) throw new IOException("export <id> <folder>");
                    Manifest m = find(store, args.get(1));
                    Path target = Reports.export(store, m, Path.of(args.get(2)));
                    out.println("Exported " + m.id + " to " + target.toAbsolutePath());
                    return 0;
                }
                case "restore": {
                    if (args.size() < 3) throw new IOException("restore <id> <server or save folder> [confirm] [--into-other]");
                    boolean confirm = args.size() > 3 && args.get(3).equals("confirm");
                    restore(store, find(store, args.get(1)), Path.of(args.get(2)), confirm, intoOther, out, Clock.systemDefaultZone());
                    return 0;
                }
                default:
                    usage(out);
                    return 2;
            }
        } catch (IOException | RuntimeException e) {
            err.println("worldbackup " + args.get(0) + ": " + e.getMessage());
            return 1;
        }
    }

    /** A source seen this recently, and marked running, is taken to be running now. */
    static final long RUNNING_MILLIS = 10 * 60_000L;

    /**
     * Restores into a stopped server or save at once: stage, then apply, taking the usual pre-restore
     * snapshot first, with the plugin's checks: a snapshot from an older build needs {@code confirm}, and one
     * taken from another folder needs {@code --into-other}. Refuses a server that's running. Only Hytale's
     * layout is known so far.
     */
    static void restore(Store store, Manifest m, Path root, boolean confirm, boolean intoOther, PrintStream out, Clock clock) throws IOException {
        Path worlds = root.resolve("universe/worlds");
        if (!Files.isDirectory(worlds)) throw new IOException(root + " doesn't look like a Hytale server or save (no universe/worlds)");
        String running = runningReason(store, m.world, root, worlds, clock);
        if (running != null) throw new IOException("Stop the server first: " + running + ".");
        if (!intoOther && m.source != null && !samePath(Path.of(m.source), root)) {
            throw new IOException(m.id + " was taken from " + m.source + ", not " + root + ". Add --into-other to restore it here anyway.");
        }
        Path dataDir = root.resolve(Backups.DATA_FOLDER);
        String build = new BootCheck(dataDir).lastBuild();
        Settings settings = new Settings(dataDir);
        settings.load();
        List<String> folders = new ArrayList<>();
        try (Stream<Path> s = Files.list(worlds)) {
            s.filter(Files::isDirectory).forEach(p -> folders.add("universe/worlds/" + p.getFileName()));
        }
        String worldFolder = guessWorldFolder(m, folders);
        SnapshotWriter writer = new SnapshotWriter(store, clock);
        RestoreJob job = new RestoreJob(store, writer, dataDir);
        SnapshotWriter.Request pre = new SnapshotWriter.Request();
        pre.root = root;
        pre.world = m.world;
        pre.selection = Profiles.hytale(worldFolder, folders, Backups.DATA_FOLDER, settings.world(m.world).bool("IncludePlayers"),
            Profiles.dataFoldersOnDisk(root));
        pre.label = "before restoring " + m.id;
        pre.by = "command line";
        pre.gameId = m.gameId;
        pre.gameBuild = build;
        pre.profile = Profiles.HYTALE;
        try (StoreLock lock = StoreLock.acquire(store, 60_000)) {
            job.stage(lock, m.id, build, confirm);
            RestoreJob.Pending done = job.apply(lock, root, pre);
            if (done.failed()) {
                String why = done.failure;
                job.cancel(lock, root, m.world);
                throw new IOException("The restore failed: " + why + ". " + root + " is as it was.");
            }
            out.println("Restored " + m.id + " into " + root + ". Undo: restore " + done.preRestoreId);
        }
    }

    /** Why the server at {@code root} looks like it's running, or null when it doesn't. */
    static String runningReason(Store store, String world, Path root, Path worlds, Clock clock) throws IOException {
        try (Stream<Path> s = Files.list(worlds)) {
            for (Path w : (Iterable<Path>) s::iterator) {
                Path lock = w.resolve(".world.lock");
                if (Files.exists(lock) && lockHeld(lock)) return "the world lock " + root.relativize(lock) + " is held";
            }
        }
        for (worldbackup.core.SourceFile src : store.sources()) {
            if (!src.running || src.lastSeen == null || !src.ownedBy(root)) continue;
            long seen;
            try {
                seen = java.time.OffsetDateTime.parse(src.lastSeen).toInstant().toEpochMilli();
            } catch (RuntimeException e) {
                continue;
            }
            if (clock.millis() - seen < RUNNING_MILLIS) {
                return "WorldBackup there was running at " + src.lastSeen + " and didn't record a clean shutdown since";
            }
        }
        return null;
    }

    /** True when another process holds a lock on the file (or keeps us from opening it at all). */
    static boolean lockHeld(Path file) {
        try (java.nio.channels.FileChannel ch = java.nio.channels.FileChannel.open(file, java.nio.file.StandardOpenOption.WRITE)) {
            java.nio.channels.FileLock l = ch.tryLock();
            if (l == null) return true;
            l.release();
            return false;
        } catch (java.nio.channels.OverlappingFileLockException e) {
            return true;
        } catch (IOException e) {
            return true;
        }
    }

    static boolean samePath(Path a, Path b) {
        String x = a.toAbsolutePath().normalize().toString(), y = b.toAbsolutePath().normalize().toString();
        return java.io.File.separatorChar == '\\' ? x.equalsIgnoreCase(y) : x.equals(y);
    }

    /** The world folder the snapshot holds: the one under universe/worlds/ with files in the manifest. */
    static String guessWorldFolder(Manifest m, List<String> folders) {
        for (Manifest.FileEntry f : m.files) {
            if (f.path.startsWith("universe/worlds/")) {
                String[] parts = f.path.split("/");
                if (parts.length > 3) return "universe/worlds/" + parts[2];
            }
        }
        return folders.isEmpty() ? "universe/worlds/default" : folders.get(0);
    }

    private static Manifest find(Store store, String id) throws IOException {
        Manifest m = store.find(id);
        if (m == null) throw new IOException("No snapshot " + id);
        return m;
    }

    static void usage(PrintStream out) {
        out.println("WorldBackup command line. The backup folder is " + Backups.defaultStoreDir() + " unless --store <folder> says otherwise.");
        out.println("  worlds                         every world in the backup folder");
        out.println("  list [world]                   worlds, or a world's snapshots");
        out.println("  budget                         whether the space settings fit");
        out.println("  verify <id>                    checks a snapshot's files against their hashes");
        out.println("  export <id> <folder>           writes a snapshot out as a plain folder");
        out.println("  restore <id> <folder> [confirm]  restores into a STOPPED server or save, keeping an undo snapshot");
        out.println("                                 (confirm: a snapshot from an older game build; --into-other: from another folder)");
        out.println("Ids contain spaces when the world's name does: put them in quotes.");
    }
}
