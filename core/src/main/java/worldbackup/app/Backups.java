package worldbackup.app;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import worldbackup.core.BootCheck;
import worldbackup.core.Budget;
import worldbackup.core.Manifest;
import worldbackup.core.RestoreJob;
import worldbackup.core.Retention;
import worldbackup.core.RetentionPolicy;
import worldbackup.core.Selection;
import worldbackup.core.SnapshotNames;
import worldbackup.core.SnapshotWriter;
import worldbackup.core.SourceFile;
import worldbackup.core.Store;
import worldbackup.core.StoreLock;
import worldbackup.core.StoreSettings;
import worldbackup.core.Trigger;

/**
 * WorldBackup running inside a game: the snapshots before the world loads, the schedule, the shutdown
 * snapshot, and what the commands and the window ask for (design/Hytale.md). Game-agnostic; the adapter
 * supplies a {@link Game} and calls {@link #startup()}, {@link #tick()}, {@link #playersChanged(int)} and
 * {@link #shutdown()}.
 *
 * <p>Everything that touches the store runs on one worker thread ({@link #submit}), so snapshots, prunes and
 * commands never overlap inside this server; {@link StoreLock} keeps other servers out.
 */
public final class Backups {
    /** Where Hytale keeps WorldBackup's data folder, relative to the server root (the command line assumes it). */
    public static final String DATA_FOLDER = "mods/Hledjolf_WorldBackup";
    /** A system property naming the store folder, ahead of every setting: test servers set it. */
    public static final String STORE_PROPERTY = "worldbackup.store";
    static final long LOCK_WAIT_MILLIS = 10 * 60_000L;

    /** One of this server's worlds. */
    public static final class WorldRef {
        public final String gameWorld;
        /** The name in the store: "Dimrill Dale", "SMP - Nether". */
        public final String name;
        public final String folder;

        WorldRef(String gameWorld, String name, String folder) {
            this.gameWorld = gameWorld;
            this.name = name;
            this.folder = folder;
        }
    }

    /** What the last start found and did, for the banner and operators. */
    public static final class BootReport {
        public String fromBuild;
        public String toBuild;
        public BootCheck.Result check;
        public final List<String> snapshots = new ArrayList<>();
        public String restored;
        /** Set when a staged restore failed: what happened, and that the world is as it was. */
        public String restoreFailure;
        public String failure;
    }

    private final Game game;
    private final Path dataDir;
    private final Clock clock;
    private final Settings settings;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "WorldBackup");
        t.setDaemon(true);
        return t;
    });

    private Store store;
    private SnapshotWriter writer;
    private RestoreJob restoreJob;
    private BootCheck bootCheck;
    private boolean readOnly;
    private final BootReport boot = new BootReport();
    private final Map<String, Instant> lastSnapshot = new HashMap<>();
    private Instant startedAt;
    private int playersOnline;
    private boolean lastPlayerLeft;
    private volatile boolean shuttingDown;
    private String lastBudgetNotice;
    private String lastFailure;
    /** The jar's MSM sidecar, read once; null when it isn't on the class path (unit tests set it). */
    String jarSidecar = readJarSidecar();
    private String sidecarNote;
    /** Tests only: runs before each world's snapshot before loading; throwing makes that snapshot fail. */
    java.util.function.Consumer<String> beforeBootSnapshot = world -> { };

    public Backups(Game game, Clock clock) {
        this.game = game;
        this.dataDir = game.dataDir();
        this.clock = clock;
        this.settings = new Settings(dataDir);
    }

    public Game game() {
        return game;
    }

    public Store store() {
        return store;
    }

    public Settings settings() {
        return settings;
    }

    public Path dataDir() {
        return dataDir;
    }

    public boolean readOnly() {
        return readOnly;
    }

    public BootReport bootReport() {
        return boot;
    }

    public RestoreJob restoreJob() {
        return restoreJob;
    }

    public String lastFailure() {
        return lastFailure;
    }

    /** Runs {@code job} on the worker thread, after anything already queued. */
    public <T> CompletableFuture<T> submit(Callable<T> job) {
        CompletableFuture<T> f = new CompletableFuture<>();
        worker.execute(() -> {
            try {
                f.complete(job.call());
            } catch (Throwable e) {
                f.completeExceptionally(e);
            }
        });
        return f;
    }

    /** The store's folder: {@code BackupDir}, or the machine-wide default. */
    public static Path storeDir(String backupDir, Path root) {
        String forced = System.getProperty(STORE_PROPERTY);
        if (forced != null && !forced.isEmpty()) return Path.of(forced);
        if (backupDir != null && !backupDir.trim().isEmpty()) {
            Path p = Path.of(backupDir.trim());
            return p.isAbsolute() ? p : root.resolve(p);
        }
        return defaultStoreDir();
    }

    public static Path defaultStoreDir() {
        String local = System.getenv("LOCALAPPDATA");
        if (local != null && !local.isEmpty()) return Path.of(local, "WorldBackup");
        return Path.of(System.getProperty("user.home"), ".local", "share", "WorldBackup");
    }

    // ---- this server's worlds --------------------------------------------------------------------------------

    /**
     * This server's worlds, named as the player knows them. A name another source already uses in the store
     * gets a suffix ("SMP (2)"); excluded worlds are left out.
     */
    public List<WorldRef> worlds() throws IOException {
        List<WorldRef> out = new ArrayList<>();
        String folderName = game.root().toAbsolutePath().normalize().getFileName().toString();
        for (String gw : game.worlds()) {
            String base = SnapshotNames.worldName(folderName, gw, game.defaultWorld());
            String name = base;
            for (int n = 2; store != null; n++) {
                SourceFile s = store.source(name);
                if (s == null || s.ownedBy(game.root()) || s.source == null) break;
                name = base + " (" + n + ")";
            }
            if (settings.excluded(name, gw)) continue;
            out.add(new WorldRef(gw, name, game.worldFolder(gw)));
        }
        return out;
    }

    public WorldRef world(String nameOrGameWorld) throws IOException {
        List<WorldRef> ws = worlds();
        if (nameOrGameWorld == null) return ws.isEmpty() ? null : ws.get(0);
        for (WorldRef w : ws) {
            if (w.name.equalsIgnoreCase(nameOrGameWorld) || w.gameWorld.equalsIgnoreCase(nameOrGameWorld)) return w;
        }
        return null;
    }

    public boolean ownWorld(String storeName) throws IOException {
        for (WorldRef w : worlds()) {
            if (w.name.equals(storeName)) return true;
        }
        return false;
    }

    Selection selection(WorldRef w) {
        List<String> folders = Profiles.worldFoldersOnDisk(game.root());
        for (String gw : game.worlds()) {
            if (!folders.contains(game.worldFolder(gw))) folders.add(game.worldFolder(gw));
        }
        List<String> plugins = game.pluginDataFolders();
        return Profiles.hytale(w.folder, folders, dataFolder(), settings.world(w.name).bool("IncludePlayers"),
            plugins != null ? plugins : Profiles.dataFoldersOnDisk(game.root()));
    }

    /** The data folder relative to the root, '/'-separated; {@link #DATA_FOLDER} when it lives elsewhere. */
    String dataFolder() {
        Path r = game.root().toAbsolutePath().normalize(), d = dataDir.toAbsolutePath().normalize();
        return d.startsWith(r) && !d.equals(r) ? r.relativize(d).toString().replace(java.io.File.separatorChar, '/') : DATA_FOLDER;
    }

    // ---- start, schedule, shutdown ---------------------------------------------------------------------------

    /**
     * Runs before the game opens any world (Hytale: setup()). Applies a staged restore, takes the pre-update,
     * mod-change or first-run snapshot, and records this server in the store. Never throws: a failure is
     * logged, reported to operators later, and the boot goes on.
     *
     * <p>The restore is its own step: whatever happens to it, the snapshots before loading run after it. Each
     * world's snapshot is recorded as it's taken, so after a partial failure the next boot retries only the
     * worlds that got none; the build and mod list are recorded once every world has its snapshot.
     */
    public BootReport startup() {
        startedAt = clock.instant();
        try {
            settings.load();
            if (settings.problem() != null) game.warn(settings.problem());
            store = new Store(storeDir(settings.server().string("BackupDir"), game.root()));
            writer = new SnapshotWriter(store, clock);
            restoreJob = new RestoreJob(store, writer, dataDir);
            bootCheck = new BootCheck(dataDir);
            readOnly = store.readOnly();
            if (readOnly) {
                boot.failure = "The backup folder " + store.root() + " was written by a newer WorldBackup. Update to back up here.";
                game.warn(boot.failure);
                return boot;
            }
            syncStoreSettings();
            for (Manifest m : store.allManifests()) lastSnapshot.merge(m.world, Instant.ofEpochMilli(m.createdMillis()), (a, b) -> a.isAfter(b) ? a : b);

            BootCheck.Result check = bootCheck.check(game.build(), game.mods());
            boot.check = check;
            boot.fromBuild = check.lastBuild;
            boot.toBuild = game.build();

            try (StoreLock lock = lock()) {
                SourceFile.markGone(lock, store, LocalDate.now(clock));
                applyRestore(lock);
                List<String> failed = bootSnapshots(lock, check);
                if (failed.isEmpty()) bootCheck.record(game.build(), game.mods());
                else boot.failure = "The backup before loading failed for " + String.join("; ", failed);
                for (WorldRef w : worlds()) writeSource(lock, w, true);
                prune(lock);
            }
            if (!boot.snapshots.isEmpty()) game.info("Backed up before loading: " + String.join(", ", boot.snapshots));
            updateSidecar();
            if (boot.failure != null) {
                lastFailure = boot.failure;
                game.warn(boot.failure);
            }
        } catch (Exception e) {
            boot.failure = "The backup before loading failed: " + e.getMessage();
            lastFailure = boot.failure;
            game.warn(boot.failure);
        }
        return boot;
    }

    /**
     * The first-run, pre-update or mod-change snapshot of each world that needs one and hasn't had it for this
     * build and mod list. Returns "world: reason" for each world whose snapshot failed.
     */
    private List<String> bootSnapshots(StoreLock lock, BootCheck.Result check) throws IOException {
        List<String> failed = new ArrayList<>();
        java.util.Set<String> done = bootCheck.snapshottedFor(game.build(), game.mods());
        Map<String, Object> extras = new LinkedHashMap<>();
        if (check.buildChanged) extras.put("fromBuild", check.lastBuild);
        if (check.modsChanged()) extras.put("mods", check.modsJson());
        for (WorldRef w : worlds()) {
            Settings.World s = settings.world(w.name);
            Trigger trigger = null;
            String label = null;
            if (check.firstRun) {
                trigger = Trigger.FIRST_RUN;
                label = "first run";
            } else if (check.buildChanged && s.bool("PreUpdateSnapshot")) {
                trigger = Trigger.PRE_UPDATE;
            } else if (check.modsChanged() && s.bool("ModChangeSnapshot")) {
                trigger = Trigger.MOD_CHANGE;
            }
            if (trigger == null || done.contains(w.name)) continue;
            try {
                beforeBootSnapshot.accept(w.name);
                SnapshotWriter.Request r = request(w, trigger, label, false, "WorldBackup");
                r.rehashAll = true; // the world as it was before this build or these mods touch it
                r.extras = extras;
                Manifest m = writer.write(lock, r).manifest;
                bootCheck.snapshotted(game.build(), game.mods(), w.name);
                boot.snapshots.add(m.id);
                lastSnapshot.put(w.name, Instant.ofEpochMilli(m.createdMillis()));
                store.log(m.id + " taken before the world loaded (" + game.root() + ")");
            } catch (IOException | RuntimeException e) {
                failed.add("'" + w.name + "': " + e.getMessage());
                store.log("snapshot of " + w.name + " before loading FAILED: " + e.getMessage());
            }
        }
        return failed;
    }

    /** The staged restore, if any: its own step, so nothing it does can stop the snapshots after it. */
    private void applyRestore(StoreLock lock) {
        RestoreJob.Pending pending = null;
        try {
            pending = restoreJob.pending();
            if (pending == null) return;
            if (pending.failed()) {
                boot.restoreFailure = restoreFailureText(pending);
                return;
            }
            WorldRef target = null;
            for (WorldRef w : worlds()) {
                if (w.name.equals(pending.world)) target = w;
            }
            if (target == null) {
                game.warn("A restore of " + pending.snapshotId + " is staged for '" + pending.world + "', which isn't one of this server's worlds; left staged");
                return;
            }
            SnapshotWriter.Request pre = request(target, Trigger.PRE_RESTORE, "before restoring " + pending.snapshotId, false, "WorldBackup");
            RestoreJob.Pending done = restoreJob.apply(lock, game.root(), pre);
            if (done == null) return;
            if (done.failed()) {
                boot.restoreFailure = restoreFailureText(done);
                fail(boot.restoreFailure);
                return;
            }
            boot.restored = done.snapshotId;
            game.info("Restored " + done.snapshotId + " (undo: restore " + done.preRestoreId + ")");
        } catch (Exception e) {
            String id = pending == null ? "the staged snapshot" : pending.snapshotId;
            boot.restoreFailure = "The restore of " + id + " failed: " + e.getMessage();
            fail(boot.restoreFailure);
        }
    }

    static String restoreFailureText(RestoreJob.Pending p) {
        return "The restore of " + p.snapshotId + " failed: " + p.failure + ". Your world is as it was. Stage it again to retry, or cancel to clear this.";
    }

    /**
     * Writes or refreshes the per-world MSM sidecar (servers with several worlds), and asks MSM to reload when
     * it changed. Called at startup, when the game has loaded its worlds, and after every settings change, so
     * the inherited defaults stay current. Never throws.
     */
    public void updateSidecar() {
        if (jarSidecar == null) return;
        try {
            List<String> names = new ArrayList<>();
            for (WorldRef w : worlds()) names.add(w.name);
            WorldSidecar.Outcome o = WorldSidecar.update(dataDir, settings, jarSidecar, names);
            sidecarNote = o == WorldSidecar.Outcome.LEFT_ALONE && names.size() > 1
                ? "Per-world settings: " + dataDir.resolve(WorldSidecar.FILE) + " was edited by hand, so WorldBackup leaves it alone. Remove or rename it to edit each world's settings in Mod Settings Manager."
                : null;
            if (o == WorldSidecar.Outcome.WRITTEN || o == WorldSidecar.Outcome.REMOVED) game.reloadSettingsWindow();
        } catch (IOException | RuntimeException e) {
            game.warn("Couldn't write the per-world settings sidecar: " + e.getMessage());
        }
    }

    /** Why per-world settings can't be edited in MSM here, or null. */
    public String sidecarNote() {
        return sidecarNote;
    }

    private static String readJarSidecar() {
        try (java.io.InputStream in = Backups.class.getResourceAsStream("/" + WorldSidecar.FILE)) {
            return in == null ? null : new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    /** Call about once a minute. Takes the scheduled snapshots that are due, on the worker thread. */
    public void tick() {
        if (shuttingDown || readOnly || store == null) return;
        submit(() -> {
            scheduledNow();
            return null;
        });
    }

    /** The adapter reports the number of players online whenever someone joins or leaves. */
    public void playersChanged(int online) {
        if (playersOnline > 0 && online == 0) lastPlayerLeft = true;
        playersOnline = online;
    }

    void scheduledNow() throws Exception {
        if (shuttingDown || readOnly) return;
        boolean leaving = lastPlayerLeft;
        lastPlayerLeft = false;
        Instant now = clock.instant();
        for (WorldRef w : worlds()) {
            Settings.World s = settings.world(w.name);
            if (!s.bool("ScheduledSnapshots")) continue;
            if (s.bool("OnlyWhilePlayersOnline") && !game.singleplayer() && game.playersOnline() == 0 && !leaving) continue;
            Instant last = lastSnapshot.getOrDefault(w.name, startedAt);
            boolean due = !now.isBefore(last.plus(s.scheduleMinutes(), ChronoUnit.MINUTES));
            if (!due && !leaving) continue;
            if (lowOnSpace()) {
                fail("Scheduled snapshot of '" + w.name + "' skipped: less than " + store.settings().minFreeMB + " MB free on the backup drive");
                continue;
            }
            game.saveWorld(w.gameWorld);
            if (s.bool("SkipUnchanged") && !SnapshotWriter.changedSince(game.root(), selection(w), writer.latest(w.name))) {
                lastSnapshot.put(w.name, now); // nothing to keep; look again after another interval
                continue;
            }
            take(w, Trigger.SCHEDULED, null, false, "schedule", s.integer("MaxCopyMBps"));
        }
    }

    /** At a clean shutdown: the shutdown snapshot of every world, then the build and mods are recorded. */
    public void shutdown() {
        shuttingDown = true;
        worker.shutdown();
        try {
            worker.awaitTermination(2, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (store == null || readOnly) return;
        try {
            for (WorldRef w : worlds()) {
                try {
                    game.saveWorld(w.gameWorld);
                } catch (Exception e) {
                    game.warn("Couldn't save '" + w.name + "' before its shutdown snapshot: " + e.getMessage());
                }
            }
            try (StoreLock lock = lock()) {
                for (WorldRef w : worlds()) {
                    Manifest m = writer.write(lock, request(w, Trigger.SHUTDOWN, null, false, "shutdown")).manifest;
                    store.log(m.id + " taken at shutdown");
                }
                bootCheck.record(game.build(), game.mods());
                for (WorldRef w : worlds()) writeSource(lock, w, false);
                prune(lock);
            }
        } catch (Exception e) {
            game.warn("The shutdown snapshot failed: " + e.getMessage());
        }
    }

    /**
     * Mod Settings Manager's live hook: re-reads the file. Only the store settings that changed go to
     * store.json, so a stale value in this config never overwrites a newer one set from another server.
     */
    public boolean settingsChanged(Map<String, Object> changed) {
        try {
            settings.load();
            List<String> storeKeys = new ArrayList<>();
            for (String k : changed.keySet()) {
                if (Settings.STORE_KEYS.contains(k)) storeKeys.add(k);
            }
            if (!storeKeys.isEmpty() && store != null && !readOnly) {
                submit(() -> {
                    try (StoreLock lock = lock()) {
                        StoreSettings current = store.settings();
                        StoreSettings fromConfig = settings.storeSettings(current);
                        if (storeKeys.contains("MaxTotalMB")) current.maxTotalMB = fromConfig.maxTotalMB;
                        if (storeKeys.contains("MinFreeMB")) current.minFreeMB = fromConfig.minFreeMB;
                        if (storeKeys.contains("WarnAtPercent")) current.warnAtPercent = fromConfig.warnAtPercent;
                        store.saveSettings(current);
                        store.log("store settings changed from " + game.root() + ": " + storeKeys + " -> MaxTotalMB=" + current.maxTotalMB
                            + " MinFreeMB=" + current.minFreeMB + " WarnAtPercent=" + current.warnAtPercent);
                        settings.fillIn(current);
                        writeSeen(current);
                        for (WorldRef w : worlds()) writeSource(lock, w, true);
                    }
                    return null;
                });
            } else if (store != null && !readOnly) {
                submit(() -> {
                    try (StoreLock lock = lock()) {
                        for (WorldRef w : worlds()) writeSource(lock, w, true);
                    }
                    return null;
                });
            }
            if (store != null) {
                submit(() -> {
                    updateSidecar(); // the inherited defaults follow the server's values
                    return null;
                });
            }
            return true;
        } catch (IOException e) {
            throw new IllegalStateException("couldn't read " + settings.file() + ": " + e.getMessage(), e);
        }
    }

    /**
     * At startup: store.json is the truth for the store settings, unless this server's config.json was
     * edited while the server was stopped (its values differ from what was last written there), in which
     * case that edit is the latest change and goes to store.json.
     */
    private void syncStoreSettings() throws IOException {
        StoreSettings inStore = store.settings();
        Path seen = dataDir.resolve("store-settings-seen.json");
        Map<String, Object> lastWritten = Files.exists(seen) ? worldbackup.core.Json.parseObject(Files.readString(seen)) : null;
        StoreSettings fromConfig = settings.storeSettings(inStore);
        Map<String, Object> configValues = Settings.storeValues(fromConfig);
        if (lastWritten != null && Files.exists(settings.file()) && !configValues.equals(lastWritten)
            && !configValues.equals(Settings.storeValues(inStore))) {
            try (StoreLock lock = lock()) {
                store.saveSettings(fromConfig);
                store.log("store settings changed from " + game.root() + " (edited while stopped): " + configValues);
            }
            inStore = fromConfig;
        }
        settings.fillIn(inStore);
        writeSeen(inStore);
    }

    /** Records the store values last written into this server's config.json (store-settings-seen.json). */
    private void writeSeen(StoreSettings s) throws IOException {
        Files.createDirectories(dataDir);
        Store.writeAtomic(dataDir.resolve("store-settings-seen.json"), worldbackup.core.Json.write(Settings.storeValues(s)));
    }

    // ---- what commands and the window do (call these on the worker thread) -----------------------------------

    /** Saves the world, then snapshots it. */
    public Manifest snapshotNow(WorldRef w, Trigger trigger, String label, boolean pinned, String by) throws Exception {
        if (readOnly) throw new IOException("This backup folder is read only: it was written by a newer WorldBackup");
        game.saveWorld(w.gameWorld);
        return take(w, trigger, label, pinned, by, settings.world(w.name).integer("MaxCopyMBps"));
    }

    private Manifest take(WorldRef w, Trigger t, String label, boolean pinned, String by, int maxMBps) throws Exception {
        try (StoreLock lock = lock()) {
            SnapshotWriter.Request r = request(w, t, label, pinned, by);
            r.maxBytesPerSecond = maxMBps <= 0 ? 0 : maxMBps * 1024L * 1024L;
            Manifest m;
            try {
                m = writer.write(lock, r).manifest;
            } catch (Exception e) {
                fail("Snapshot of '" + w.name + "' failed: " + e.getMessage());
                throw e;
            }
            lastSnapshot.put(w.name, Instant.ofEpochMilli(m.createdMillis()));
            store.log(m.id + " taken by " + by + (m.consistent ? "" : " (changed while copying: " + m.changedDuringCopy + ")"));
            try { // the snapshot is safe now; what follows failing doesn't make it a failed snapshot
                writeSource(lock, w, true);
                prune(lock);
            } catch (Exception e) {
                fail("Snapshot " + m.id + " was taken, but tidying up after it failed: " + e.getMessage());
            }
            return m;
        }
    }

    public Retention.Result prune(StoreLock lock) throws IOException {
        SourceFile.markGone(lock, store, LocalDate.now(clock));
        Map<String, RetentionPolicy> policies = new HashMap<>();
        for (WorldRef w : worlds()) policies.put(w.name, settings.world(w.name).policy());
        String staged = restoreJob.protectedId();
        Retention.Result r = new Retention(store).prune(lock, policies, store.settings(), staged == null ? java.util.Set.of() : java.util.Set.of(staged));
        if (!r.removed.isEmpty()) store.log("pruned " + r.removed.size() + " snapshots, freed " + Budget.size(r.bytesFreed));
        if (!r.unreadable.isEmpty()) {
            for (String u : r.unreadable) game.warn("Unreadable snapshot " + u);
            fail("Space can't be freed until " + unreadableName(r.unreadable.get(0)) + " is fixed or removed"
                + (r.unreadable.size() > 1 ? " (and " + (r.unreadable.size() - 1) + " more; see the log)" : "") + ".");
        }
        if (r.overCapProtectedOnly) fail("The backups use " + Budget.size(r.totalBytes) + ", over the " + Budget.size(r.capBytes)
            + " cap, and only pinned snapshots and milestones are left. Unpin something, lower KeepMilestones, or raise MaxTotalMB.");
        return r;
    }

    /** "snapshots/World/x.json: reason" without the reason. */
    static String unreadableName(String line) {
        int colon = line.indexOf(".json:");
        return colon < 0 ? line : line.substring(0, colon + 5);
    }

    public StoreLock lock() throws IOException {
        try {
            return StoreLock.acquire(store, 0);
        } catch (IOException busy) {
            if (store.readOnly()) throw busy;
            game.info("Waiting for another server's backup to finish (" + store.root() + ")");
            return StoreLock.acquire(store, LOCK_WAIT_MILLIS);
        }
    }

    SnapshotWriter.Request request(WorldRef w, Trigger t, String label, boolean pinned, String by) {
        SnapshotWriter.Request r = new SnapshotWriter.Request();
        r.root = game.root();
        r.selection = selection(w);
        r.world = w.name;
        r.trigger = t;
        r.label = label;
        r.pinned = pinned;
        r.by = by;
        r.gameId = game.id();
        r.gameBuild = game.build();
        r.gameRevision = game.revision();
        r.profile = Profiles.HYTALE;
        return r;
    }

    /** Rewrites one of this server's source files (after staging or cancelling a restore). */
    public void refreshSource(StoreLock lock, String worldName) throws IOException {
        for (WorldRef w : worlds()) {
            if (w.name.equals(worldName)) writeSource(lock, w, true);
        }
    }

    /**
     * On a server with several worlds, what else a restore rolls back: the files the worlds share. Null when
     * the server has one world.
     */
    public String sharedDataWarning() throws IOException {
        if (game.worlds().size() < 2) return null;
        return "This also rolls back player inventories, bans and mod data for every world on this server.";
    }

    void writeSource(StoreLock lock, WorldRef w, boolean running) throws IOException {
        SourceFile s = new SourceFile();
        s.world = w.name;
        s.source = game.root().toAbsolutePath().normalize().toString();
        s.kind = game.singleplayer() ? "singleplayer" : "server";
        s.gameId = game.id();
        s.gameBuild = game.build();
        s.gameRevision = game.revision();
        s.gamePatchline = game.patchline();
        s.worldBackup = game.modVersion();
        s.mods.addAll(game.mods());
        Settings.World ws = settings.world(w.name);
        s.settings.putAll(ws.values);
        s.settingsFrom.putAll(ws.from);
        Manifest last = writer.latest(w.name);
        s.lastSnapshot = last == null ? null : last.id;
        RestoreJob.Pending pending = restoreJob.pending(w.name);
        s.pendingRestore = pending == null || pending.failed() ? null : pending.snapshotId;
        s.lastSeen = OffsetDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS).toString();
        s.running = running;
        long[] builtIn = game.builtInBackups();
        if (builtIn != null) {
            s.builtInBackups = new LinkedHashMap<>();
            s.builtInBackups.put("enabled", game.builtInBackupsEnabled());
            s.builtInBackups.put("bytes", builtIn[0]);
            s.builtInBackups.put("files", builtIn[1]);
        }
        store.writeSource(s);
    }

    private boolean lowOnSpace() {
        try {
            Files.createDirectories(store.root());
            long free = Files.getFileStore(store.root()).getUsableSpace();
            return free < store.settings().minFreeMB * 1024L * 1024L;
        } catch (IOException e) {
            return false;
        }
    }

    /** Logs a failure and tells operators (AnnounceToOps), once per distinct message. */
    void fail(String message) {
        game.warn(message);
        if (!message.equals(lastFailure) && settings.server().bool("AnnounceToOps")) game.tellOps("[WorldBackup] " + message);
        lastFailure = message;
    }

    /** Tells operators about a budget warning or error once per change of the result (AnnounceToOps). */
    public void announceBudget(Budget.Report r) {
        String worst = null;
        for (Budget.Problem p : r.problems) {
            if (p.level != Budget.Level.INFO) {
                worst = p.toString();
                break;
            }
        }
        if (worst != null && !worst.equals(lastBudgetNotice) && settings.server().bool("AnnounceToOps")) game.tellOps("[WorldBackup] " + worst);
        lastBudgetNotice = worst;
    }

    /** Lines for operators when the server has booted: what the start found and did. */
    public List<String> bootNotices() {
        List<String> out = new ArrayList<>();
        String b = banner();
        if (b != null) out.add("[WorldBackup] " + b);
        return out;
    }

    /** The window's banner after this start, or null when there's nothing to say. */
    public String banner() {
        if (boot.failure != null) {
            String last = lastGoodSnapshotTime();
            return boot.failure + (last == null ? "" : " Last good snapshot: " + last + ".");
        }
        if (boot.restoreFailure != null) return boot.restoreFailure;
        if (boot.restored != null) return "Restored " + boot.restored + ". To undo it, restore its pre-restore snapshot.";
        if (boot.snapshots.isEmpty() || boot.check == null) return null;
        if (boot.check.firstRun) return "WorldBackup's first run: your world was backed up.";
        if (boot.check.buildChanged) return "Hytale updated (" + boot.fromBuild + " to " + boot.toBuild + "). Your worlds were backed up first.";
        return "Mods changed (" + modDiff(boot.check) + "). Your world was backed up first.";
    }

    static String modDiff(BootCheck.Result r) {
        List<String> parts = new ArrayList<>();
        for (BootCheck.Mod m : r.added) parts.add("+" + shortId(m.id) + " " + m.version);
        for (BootCheck.Mod m : r.removed) parts.add("-" + shortId(m.id));
        for (BootCheck.Mod m : r.updated) parts.add(shortId(m.id) + " " + m.version + " updated");
        return String.join(", ", parts);
    }

    private static String shortId(String id) {
        int colon = id.indexOf(':');
        return colon < 0 ? id : id.substring(colon + 1);
    }

    private String lastGoodSnapshotTime() {
        Instant best = null;
        for (Instant i : lastSnapshot.values()) {
            if (best == null || i.isAfter(best)) best = i;
        }
        return best == null ? null : OffsetDateTime.ofInstant(best, clock.getZone()).truncatedTo(ChronoUnit.MINUTES).toLocalDateTime().toString().replace('T', ' ');
    }

    /** When the next scheduled snapshot of {@code w} is due, or null when scheduling is off. */
    public Instant nextScheduled(WorldRef w) {
        Settings.World s = settings.world(w.name);
        if (!s.bool("ScheduledSnapshots") || startedAt == null) return null;
        return lastSnapshot.getOrDefault(w.name, startedAt).plus(s.scheduleMinutes(), ChronoUnit.MINUTES);
    }
}
