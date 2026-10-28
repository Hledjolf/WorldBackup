package worldbackup.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import worldbackup.app.Backups;
import worldbackup.app.Reports;
import worldbackup.app.TextCommands;
import worldbackup.core.Budget;
import worldbackup.core.Builds;
import worldbackup.core.Manifest;
import worldbackup.core.RestoreJob;
import worldbackup.core.Retention;
import worldbackup.core.SourceFile;

/**
 * The window (design/Hytale.md, "The window"), as {@link UiOps} on the documents in
 * {@code Common/UI/Custom/WorldBackup/}. Worlds on the left in three groups, the chosen world's snapshots on
 * the right, the actions with their commands beside them, the banner, a staged restore, and the status line.
 *
 * <p>Every action is the matching {@code /worldbackup} subcommand, run through {@link TextCommands} as this
 * player, so permissions and messages are the same as in chat. Store reads and actions run on the service's
 * worker thread; their results arrive through {@code push}. Clicks carry the list generation they were bound
 * in, and clicks from an older list are ignored.
 */
public final class BackupWindow {
    public static final String DOC = "WorldBackup/Window.ui";
    static final String GROUP_ROW = "WorldBackup/GroupRow.ui";
    static final String WORLD_ROW = "WorldBackup/WorldRow.ui";
    static final String SNAPSHOT_ROW = "WorldBackup/SnapshotRow.ui";
    static final String NOTE_ROW = "WorldBackup/NoteRow.ui";
    static final String CONFIRM_BAR = "WorldBackup/ConfirmBar.ui";
    static final String ACTIONS = "WorldBackup/Actions.ui";
    static final int MAX_ROWS = 200;
    static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final Backups b;
    private final TextCommands text;
    private final TextCommands.Actor actor;
    private final Consumer<UiOps> push;
    private final ZoneId zone;

    private String world;
    private String picked;
    private boolean confirming;
    private int gen;
    private volatile boolean closed;

    public BackupWindow(Backups backups, TextCommands.Actor actor, Consumer<UiOps> push, ZoneId zone) {
        this.b = backups;
        this.text = new TextCommands(backups);
        this.actor = actor;
        this.push = push;
        this.zone = zone;
    }

    /** The first ops after the document is appended: placeholders, then the real contents arrive by push. */
    public UiOps open() {
        UiOps ops = new UiOps();
        ops.text("#WorldTitle", "Loading backups...");
        ops.bind("#Pending #PendingCancel", "Activating", Map.of("Action", "cancelPending"));
        refreshLater(null);
        return ops;
    }

    public void close() {
        closed = true;
    }

    /** A click or a field change. Returns what to change right away; the rest is pushed when it's done. */
    public UiOps onEvent(String action, Map<String, Object> data) {
        UiOps ops = new UiOps();
        if (action == null) return ops;
        String g = str(data.get("Gen"));
        boolean current = g == null || g.equals(String.valueOf(gen));
        switch (action) {
            case "world":
                if (!current) return ops;
                world = str(data.get("World"));
                picked = null;
                confirming = false;
                refreshLater(null);
                return ops;
            case "pick":
                if (!current) return ops;
                picked = str(data.get("Id"));
                confirming = false;
                refreshLater(null);
                return ops;
            case "now":
                return run(ops, "Backing up...", List.of("now"));
            case "milestone": {
                String label = str(data.get("@Label"));
                if (label == null || label.isBlank()) return ops.text("#Output", "Type a name for the milestone first, then press Milestone.");
                List<String> args = new ArrayList<>(List.of("milestone"));
                args.addAll(TextCommands.split(label));
                return run(ops, "Backing up...", args);
            }
            case "budget":
                return run(ops, "Checking the space...", List.of("budget", world == null ? "" : world));
            case "verify":
                return needsPick(ops) ? ops : run(ops, "Checking every block of " + picked + "...", List.of("verify", picked));
            case "export":
                return needsPick(ops) ? ops : run(ops, "Exporting " + picked + "...", List.of("export", picked));
            case "pin": {
                if (needsPick(ops)) return ops;
                String id = picked;
                return runLater(ops, "", () -> List.of(isPinned(id) ? "unpin" : "pin", id)); // reads the store: on the worker
            }
            case "restore":
                if (needsPick(ops)) return ops;
                confirming = true;
                b.submit(() -> {
                    push(confirmOps());
                    return null;
                });
                return ops;
            case "confirm":
                if (!confirming || picked == null) return ops;
                confirming = false;
                ops.clear("#ConfirmBox").visible("#ConfirmBox", false);
                String id = picked;
                return runLater(ops, "Staging the restore...", () -> olderThanGame(id) ? List.of("restore", id, "confirm") : List.of("restore", id));
            case "cancelConfirm":
                confirming = false;
                return ops.clear("#ConfirmBox").visible("#ConfirmBox", false).text("#Output", "Restore not staged.");
            case "cancelPending":
                return run(ops, "", List.of("restore", "cancel"));
            default:
                return ops;
        }
    }

    // ---- actions --------------------------------------------------------------------------------------------

    private UiOps run(UiOps ops, String working, List<String> args) {
        List<String> clean = new ArrayList<>();
        for (String a : args) {
            if (a != null && !a.isEmpty()) clean.add(a);
        }
        if (!working.isEmpty()) ops.text("#Output", working);
        var f = text.run(actor, clean);
        if (f == null) return ops;
        f.whenComplete((lines, err) -> {
            String out = err != null ? "Failed: " + err.getMessage() : String.join("  ", lines);
            refreshLater(out);
        });
        return ops;
    }

    /** Like {@link #run}, with the arguments worked out on the worker thread (they need the store). */
    private UiOps runLater(UiOps ops, String working, java.util.concurrent.Callable<List<String>> args) {
        if (!working.isEmpty()) ops.text("#Output", working);
        b.submit(() -> {
            run(new UiOps(), "", args.call());
            return null;
        }).exceptionally(err -> {
            refreshLater("Failed: " + err.getMessage());
            return null;
        });
        return ops;
    }

    private boolean needsPick(UiOps ops) {
        if (picked != null) return false;
        ops.text("#Output", "Pick a snapshot in the list first.");
        return true;
    }

    private boolean isPinned(String id) {
        try {
            Manifest m = b.store().find(id);
            return m != null && m.pinned;
        } catch (IOException e) {
            return false;
        }
    }

    private boolean olderThanGame(String id) {
        try {
            Manifest m = b.store().find(id);
            return m != null && m.gameBuild != null && Builds.compare(m.gameBuild, b.game().build()) < 0;
        } catch (IOException e) {
            return false;
        }
    }

    private UiOps confirmOps() throws IOException {
        UiOps ops = new UiOps();
        Manifest m = b.store().find(picked);
        if (m == null) return ops.text("#Output", "That snapshot no longer exists.");
        String msg = "Restore " + m.id + " at the next start? The world as it is then is backed up first, so you can undo it.";
        if (olderThanGame(m.id)) msg += " It's from build " + m.gameBuild + ", older than this game's " + b.game().build() + "; Hytale will upgrade it again when it loads.";
        String shared = b.sharedDataWarning();
        if (shared != null) msg += " " + shared;
        ops.clear("#ConfirmBox").append("#ConfirmBox", CONFIRM_BAR).visible("#ConfirmBox", true);
        ops.text("#ConfirmBox[0] #Text", msg);
        ops.bind("#ConfirmBox[0] #ConfirmButton", "Activating", Map.of("Action", "confirm"));
        ops.bind("#ConfirmBox[0] #CancelButton", "Activating", Map.of("Action", "cancelConfirm"));
        return ops;
    }

    // ---- drawing --------------------------------------------------------------------------------------------

    /** Rebuilds everything on the worker thread and pushes it; {@code output} goes in the output line. */
    private void refreshLater(String output) {
        b.submit(() -> {
            try {
                UiOps ops = draw();
                if (output != null) ops.text("#Output", output);
                push(ops);
            } catch (Exception e) {
                push(new UiOps().text("#Output", "Couldn't read the backups: " + e.getMessage()));
            }
            return null;
        });
    }

    private void push(UiOps ops) {
        if (!closed) push.accept(ops);
    }

    /** The whole window's contents. Package-private for tests. */
    UiOps draw() throws IOException {
        gen++;
        UiOps ops = new UiOps();
        if (b.store() == null) {
            ops.text("#WorldTitle", "WorldBackup isn't running");
            ops.text("#WorldMeta", b.bootReport().failure == null ? "" : b.bootReport().failure);
            return ops;
        }
        Retention.Usage u = Retention.Usage.measure(b.store());
        Set<String> here = new HashSet<>();
        for (Backups.WorldRef w : b.worlds()) here.add(w.name);
        if (world == null) world = here.isEmpty() ? null : b.worlds().get(0).name;

        drawNav(ops, u, here);
        boolean own = world != null && here.contains(world);
        drawWorld(ops, u, own);
        drawActions(ops, own);
        drawBannerAndPending(ops);
        drawStatus(ops, u);
        return ops;
    }

    private void drawNav(UiOps ops, Retention.Usage u, Set<String> here) throws IOException {
        Map<String, int[]> counts = new LinkedHashMap<>();
        Map<String, Long> unique = new LinkedHashMap<>();
        for (Manifest m : u.manifests) {
            counts.computeIfAbsent(m.world, k -> new int[1])[0]++;
            unique.merge(m.world, u.uniqueBytes(m), Long::sum);
        }
        List<String> mine = new ArrayList<>(), others = new ArrayList<>(), gone = new ArrayList<>();
        for (String w : Reports.storeWorlds(b.store())) {
            SourceFile s = b.store().source(w);
            if (here.contains(w)) mine.add(w);
            else if (s != null && s.gone()) gone.add(w);
            else others.add(w);
        }
        for (String w : here) {
            if (!mine.contains(w)) mine.add(w);
        }
        ops.clear("#Nav");
        int[] row = {0};
        group(ops, row, b.game().singleplayer() ? "This world" : "Here", mine, counts, unique);
        group(ops, row, "Other worlds on this computer", others, counts, unique);
        group(ops, row, "Deleted worlds", gone, counts, unique);
        ops.text("#StoreLine", "Backup folder: " + b.store().root());
    }

    private void group(UiOps ops, int[] row, String title, List<String> worlds, Map<String, int[]> counts, Map<String, Long> unique) {
        if (worlds.isEmpty()) return;
        ops.append("#Nav", GROUP_ROW);
        ops.text("#Nav[" + row[0]++ + "] #Title", title);
        for (String w : worlds) {
            ops.append("#Nav", WORLD_ROW);
            String sel = "#Nav[" + row[0]++ + "]";
            ops.text(sel + " #Button", (w.equals(world) ? "> " : "") + w);
            int n = counts.containsKey(w) ? counts.get(w)[0] : 0;
            ops.text(sel + " #Count", n + ", " + Budget.size(unique.getOrDefault(w, 0L)));
            ops.bind(sel + " #Button", "Activating", Map.of("Action", "world", "World", w, "Gen", String.valueOf(gen)));
        }
    }

    private void drawWorld(UiOps ops, Retention.Usage u, boolean own) throws IOException {
        ops.clear("#Snapshots");
        if (world == null) {
            ops.text("#WorldTitle", "No worlds yet");
            ops.text("#WorldMeta", "A world appears here once it has been created and backed up.");
            ops.text("#ListHeader", "");
            ops.text("#Selected", "");
            return;
        }
        ops.text("#WorldTitle", world);
        SourceFile s = b.store().source(world);
        String meta;
        if (own) {
            meta = b.game().singleplayer() ? "Singleplayer: WorldBackup is enabled in this world only." : "This server's world.";
            meta += " Hytale " + b.game().build() + ".";
        } else if (s != null && s.gone()) {
            meta = "Deleted on " + s.sourceGone + ". Export brings it back: List, Verify and Export only.";
        } else {
            meta = (s == null ? "Another server or save." : "Another " + (s.kind == null ? "source" : s.kind) + ", last seen "
                + day(s.lastSeen) + ", Hytale " + s.gameBuild + ", " + s.mods.size() + " mods.") + " List, Verify and Export only.";
        }
        ops.text("#WorldMeta", meta);
        List<Manifest> ms = b.store().manifests(world);
        ops.text("#ListHeader", ms.size() + " snapshots, newest first");
        if (ms.isEmpty()) {
            ops.append("#Snapshots", NOTE_ROW);
            ops.text("#Snapshots[0] #Text", "No snapshots yet. Back up now takes the first.");
        }
        int row = 0;
        boolean pickedFound = false;
        for (int i = ms.size() - 1; i >= 0 && row < MAX_ROWS; i--) {
            Manifest m = ms.get(i);
            ops.append("#Snapshots", SNAPSHOT_ROW);
            String sel = "#Snapshots[" + row++ + "]";
            ops.text(sel + " #Text", (m.id.equals(picked) ? "> " : "") + rowText(m, u));
            ops.text(sel + " #Button", m.id.equals(picked) ? "Picked" : "Pick");
            ops.bind(sel + " #Button", "Activating", Map.of("Action", "pick", "Id", m.id, "Gen", String.valueOf(gen)));
            pickedFound |= m.id.equals(picked);
        }
        if (!pickedFound) picked = null;
        ops.text("#Selected", picked == null ? "Pick a snapshot for Restore, Pin, Verify and Export." : "Picked: " + picked);
    }

    String rowText(Manifest m, Retention.Usage u) {
        StringBuilder t = new StringBuilder();
        t.append(OffsetDateTime.parse(m.created).atZoneSameInstant(zone).format(WHEN));
        t.append("   ").append(m.trigger.id);
        t.append("   ").append(m.gameBuild == null ? "?" : m.gameBuild);
        t.append("   ").append(Budget.size(m.totalBytes())).append(", ").append(Budget.size(u.uniqueBytes(m))).append(" unique");
        if (m.label != null) t.append("   \"").append(m.label).append('"');
        Object mods = m.extras.get("mods");
        if (mods instanceof Map) t.append("   ").append(modsShort((Map<?, ?>) mods));
        if (m.pinned) t.append("   [pinned]");
        if (!m.consistent) t.append("   [changed while copying]");
        return t.toString();
    }

    static String modsShort(Map<?, ?> diff) {
        List<String> parts = new ArrayList<>();
        String[][] kinds = {{"added", "+"}, {"removed", "-"}, {"updated", "~"}};
        for (String[] k : kinds) {
            Object v = diff.get(k[0]);
            if (!(v instanceof List)) continue;
            for (Object x : (List<?>) v) {
                String s = x.toString();
                int colon = s.indexOf(':');
                parts.add(k[1] + (colon < 0 ? s : s.substring(colon + 1)));
            }
        }
        return parts.isEmpty() ? "" : "(" + String.join(", ", parts) + ")";
    }

    private void drawActions(UiOps ops, boolean own) throws IOException {
        ops.clear("#Actions");
        if (world == null) return;
        ops.append("#Actions", ACTIONS);
        String a = "#Actions[0] ";
        String id = picked == null ? "<id>" : picked;
        ops.text(a + "#NowCmd", "/worldbackup now");
        ops.text(a + "#BudgetCmd", "/worldbackup budget");
        ops.text(a + "#MilestoneCmd", "/worldbackup milestone <name>");
        ops.text(a + "#RestoreCmd", "/worldbackup restore " + id);
        boolean pinned = picked != null && isPinned(picked);
        ops.text(a + "#PinButton", pinned ? "Unpin" : "Pin");
        ops.text(a + "#PinCmd", "/worldbackup " + (pinned ? "unpin " : "pin ") + id);
        ops.text(a + "#VerifyCmd", "/worldbackup verify " + id);
        ops.text(a + "#ExportCmd", "/worldbackup export " + id);
        for (String only : new String[] {"#NowButton", "#NowCmd", "#Label", "#MilestoneButton", "#MilestoneCmd", "#RestoreButton", "#RestoreCmd", "#PinButton", "#PinCmd"}) {
            ops.visible(a + only, own);
        }
        ops.bind(a + "#NowButton", "Activating", Map.of("Action", "now"));
        ops.bind(a + "#BudgetButton", "Activating", Map.of("Action", "budget"));
        ops.bind(a + "#MilestoneButton", "Activating", Map.of("Action", "milestone", "@Label", a + "#Label.Value"));
        ops.bind(a + "#RestoreButton", "Activating", Map.of("Action", "restore"));
        ops.bind(a + "#PinButton", "Activating", Map.of("Action", "pin"));
        ops.bind(a + "#VerifyButton", "Activating", Map.of("Action", "verify"));
        ops.bind(a + "#ExportButton", "Activating", Map.of("Action", "export"));
        if (!confirming) ops.clear("#ConfirmBox").visible("#ConfirmBox", false);
    }

    private void drawBannerAndPending(UiOps ops) throws IOException {
        String banner = b.banner();
        ops.visible("#Banner", banner != null);
        ops.text("#BannerText", banner == null ? "" : banner);
        RestoreJob.Pending p = b.restoreJob() == null ? null : b.restoreJob().pending();
        ops.visible("#Pending", p != null);
        ops.text("#PendingText", p == null ? "" : p.failed()
            ? "The restore of " + p.snapshotId + " failed: " + p.failure + ". Your world is as it was. Cancel clears this."
            : "Restore pending: " + p.snapshotId + ". Restart to apply.");
    }

    private void drawStatus(UiOps ops, Retention.Usage u) throws IOException {
        List<String> parts = new ArrayList<>();
        Instant last = null;
        for (Manifest m : u.manifests) {
            Instant t = Instant.ofEpochMilli(m.createdMillis());
            if (last == null || t.isAfter(last)) last = t;
        }
        if (last != null) parts.add("Last snapshot " + last.atZone(zone).format(WHEN) + ".");
        Backups.WorldRef w = b.world(null);
        Instant next = w == null ? null : b.nextScheduled(w);
        parts.add(next == null ? "Scheduled snapshots off." : "Next scheduled " + next.atZone(zone).format(WHEN) + ".");
        long cap = b.store().settings().maxTotalBytes();
        String free = "";
        try {
            free = ", " + Budget.size(Files.getFileStore(b.store().root()).getUsableSpace()) + " free";
        } catch (IOException ignored) {
            // the folder isn't there yet
        }
        parts.add("Backups use " + Budget.size(u.total) + " of " + Budget.size(cap) + free + ".");
        Budget.Report r = text.budget();
        String worst = null;
        for (Budget.Problem p : r.problems) {
            if (p.level != Budget.Level.INFO) {
                worst = p.message;
                break;
            }
        }
        if (worst != null) parts.add(worst);
        long[] builtIn = b.game().builtInBackups();
        if (builtIn != null && builtIn[0] > 0) {
            parts.add("Hytale's built-in backups " + (b.game().builtInBackupsEnabled() ? "are also on" : "folder") + ": "
                + Budget.size(builtIn[0]) + " in backup\\ (shown, never changed).");
        }
        if (b.lastFailure() != null) parts.add("Last problem: " + b.lastFailure());
        if (b.sidecarNote() != null) parts.add(b.sidecarNote());
        ops.text("#Status", String.join(" ", parts));
    }

    private static String day(String iso) {
        if (iso == null) return "never";
        return iso.length() >= 10 ? iso.substring(0, 10) : iso;
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }
}
