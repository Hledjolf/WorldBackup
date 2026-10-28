package worldbackup.app;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import worldbackup.core.Budget;
import worldbackup.core.Manifest;
import worldbackup.core.RestoreJob;
import worldbackup.core.Retention;
import worldbackup.core.StoreLock;
import worldbackup.core.Trigger;
import worldbackup.core.Verify;

/**
 * {@code /worldbackup} (alias {@code /wbak}) as text, for chat and the console (design/Hytale.md, "Commands").
 * With no arguments a player gets the window instead; the adapter handles that. Every action runs on the
 * service's worker thread, and the lines come back when it's done.
 */
public final class TextCommands {
    public static final String NAME = "worldbackup";
    public static final String ALIAS = "wbak";
    public static final String CREATE = "worldbackup.create";
    public static final String ADMIN = "worldbackup.admin";

    /**
     * Who's asking. Operators and the singleplayer owner have both permissions. Asked at every action, not
     * once, so a window opened before {@code /op self} works without reopening it.
     */
    public static final class Actor {
        public final String name;
        private final Predicate<String> has;

        public Actor(String name, Predicate<String> has) {
            this.name = name;
            this.has = has;
        }

        public Actor(String name, boolean canCreate, boolean canAdmin) {
            this(name, p -> p.equals(CREATE) ? canCreate : p.equals(ADMIN) && canAdmin);
        }

        public boolean canCreate() {
            return has.test(CREATE);
        }

        public boolean canAdmin() {
            return has.test(ADMIN);
        }
    }

    private final Backups b;

    public TextCommands(Backups backups) {
        this.b = backups;
    }

    public static List<String> usage() {
        return List.of(
            "/worldbackup  opens the window",
            "/worldbackup list [world]  snapshots, newest first",
            "/worldbackup worlds  every world in the backup folder",
            "/worldbackup budget [world]  whether the space settings fit",
            "/worldbackup now [label]  saves the world, then backs it up",
            "/worldbackup milestone <label>  like now, but pinned",
            "/worldbackup verify <id>  checks a snapshot's files",
            "/worldbackup pin <id>  /  unpin <id>",
            "/worldbackup restore <id> [confirm]  restores at the next start",
            "/worldbackup restore cancel",
            "/worldbackup prune  applies the keep settings now",
            "/worldbackup export <id>  writes a snapshot out as a plain folder");
    }

    /** Splits a typed line into words; "double quotes" keep spaces. The command name itself is dropped. */
    public static List<String> split(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false, any = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                quoted = !quoted;
                any = true;
            } else if (Character.isWhitespace(c) && !quoted) {
                if (any) out.add(cur.toString());
                cur.setLength(0);
                any = false;
            } else {
                cur.append(c);
                any = true;
            }
        }
        if (any) out.add(cur.toString());
        if (!out.isEmpty()) {
            String first = out.get(0).toLowerCase(Locale.ROOT);
            if (first.startsWith("/")) first = first.substring(1);
            if (first.equals(NAME) || first.equals(ALIAS)) out.remove(0);
        }
        return out;
    }

    /** Runs a subcommand. Returns null for no arguments (the window), so the caller can open it. */
    public CompletableFuture<List<String>> run(Actor actor, List<String> args) {
        if (args.isEmpty()) return null;
        String sub = args.get(0).toLowerCase(Locale.ROOT);
        List<String> rest = args.subList(1, args.size());
        String needs = switch (sub) {
            case "now", "milestone", "verify" -> actor.canCreate() ? null : CREATE;
            case "pin", "unpin", "restore", "prune", "export" -> actor.canAdmin() ? null : ADMIN;
            default -> null;
        };
        if (needs != null) return done("You need the " + needs + " permission for that (operators have it).");
        return b.submit(() -> {
            try {
                return dispatch(actor, sub, rest);
            } catch (IOException | RuntimeException e) {
                return List.of("/worldbackup " + sub + " failed: " + e.getMessage());
            }
        });
    }

    private List<String> dispatch(Actor actor, String sub, List<String> rest) throws Exception {
        switch (sub) {
            case "help":
                return usage();
            case "list": {
                String world = rest.isEmpty() ? defaultWorld() : String.join(" ", rest);
                return world == null ? List.of("No world to list.") : Reports.list(b.store(), world);
            }
            case "worlds":
                return Reports.worlds(b.store(), hereNames());
            case "budget": {
                Budget.Report r = budget();
                return Reports.budget(r, rest.isEmpty() ? null : String.join(" ", rest));
            }
            case "now":
            case "milestone": {
                boolean milestone = sub.equals("milestone");
                String label = rest.isEmpty() ? null : String.join(" ", rest);
                if (milestone && label == null) return List.of("Name the milestone: /worldbackup milestone <label>");
                List<String> out = new ArrayList<>();
                for (Backups.WorldRef w : b.worlds()) {
                    Manifest m = b.snapshotNow(w, milestone ? Trigger.MILESTONE : Trigger.MANUAL, label, milestone, actor.name);
                    out.add("Backed up " + m.id + ": " + Budget.size(m.totalBytes()) + ", " + Budget.size(m.addedBytes) + " new, "
                        + m.tookMillis + " ms" + (m.consistent ? "" : " (some files changed while copying)") + ".");
                }
                return out.isEmpty() ? List.of("No worlds to back up here.") : out;
            }
            case "verify": {
                Manifest m = find(rest);
                String problem;
                try (StoreLock lock = b.lock()) { // damaged blocks are deleted, so the next snapshot writes them again
                    problem = Verify.heal(lock, b.store(), m);
                    b.store().log("verify " + m.id + " by " + actor.name + ": " + (problem == null ? "whole" : problem));
                }
                return List.of(problem == null ? m.id + " is whole: every block matches its hash."
                    : m.id + " is damaged: " + problem + ". Damaged blocks were removed; the next snapshot of an unchanged file writes them again.");
            }
            case "pin":
            case "unpin": {
                Manifest found = find(rest);
                if (!b.ownWorld(found.world)) return List.of("'" + found.world + "' belongs to another server or save; pin it from there.");
                try (StoreLock lock = b.lock()) {
                    Manifest m = b.store().find(found.id); // read again under the lock: a prune may have removed it
                    if (m == null) return List.of(found.id + " no longer exists.");
                    m.pinned = sub.equals("pin");
                    b.store().rewriteManifest(m);
                    b.store().log(sub + " " + m.id + " by " + actor.name);
                }
                return List.of((sub.equals("pin") ? "Pinned " : "Unpinned ") + found.id + ".");
            }
            case "restore": {
                if (!rest.isEmpty() && rest.get(0).equalsIgnoreCase("cancel")) {
                    RestoreJob.Pending p = b.restoreJob().pending();
                    if (p == null) return List.of("No restore is staged.");
                    try (StoreLock lock = b.lock()) {
                        b.restoreJob().cancel(lock, b.game().root(), p.world);
                        b.refreshSource(lock, p.world);
                    }
                    return List.of((p.failed() ? "Cleared the failed restore of " : "Cancelled the restore of ") + p.snapshotId + ".");
                }
                boolean confirm = rest.size() > 1 && rest.get(rest.size() - 1).equalsIgnoreCase("confirm");
                Manifest m = find(confirm ? rest.subList(0, rest.size() - 1) : rest);
                if (!b.ownWorld(m.world)) return List.of("'" + m.world + "' belongs to another server or save. Restore it from there, or use export.");
                try (StoreLock lock = b.lock()) {
                    b.restoreJob().stage(lock, m.id, b.game().build(), confirm);
                    b.refreshSource(lock, m.world);
                }
                List<String> out = new ArrayList<>();
                out.add("Restore of " + m.id + " staged. Restart to apply it; /worldbackup restore cancel to call it off.");
                String shared = b.sharedDataWarning();
                if (shared != null) out.add(shared);
                return out;
            }
            case "prune": {
                try (StoreLock lock = b.lock()) {
                    Retention.Result r = b.prune(lock);
                    List<String> out = new ArrayList<>();
                    out.add("Removed " + r.removed.size() + " snapshots, freed " + Budget.size(r.bytesFreed) + ". The backups use "
                        + Budget.size(r.totalBytes) + " of " + Budget.size(r.capBytes) + ".");
                    for (Manifest m : r.removed) out.add("  " + m.id);
                    return out;
                }
            }
            case "export": {
                Manifest m = find(rest);
                Path target = b.dataDir().resolve("exports").resolve(m.id);
                Reports.export(b.store(), m, target);
                b.store().log("export " + m.id + " to " + target + " by " + actor.name);
                return List.of("Exported " + m.id + " to " + target);
            }
            default:
                return usage();
        }
    }

    public Budget.Report budget() throws IOException {
        long[] builtIn = b.game().builtInBackups();
        Budget.Report r = Budget.check(Reports.budgetInput(b.store(), b.settings().server(), builtIn == null ? -1 : builtIn[0]));
        b.announceBudget(r);
        return r;
    }

    private String defaultWorld() throws IOException {
        Backups.WorldRef w = b.world(null);
        return w == null ? null : w.name;
    }

    private Set<String> hereNames() throws IOException {
        Set<String> out = new HashSet<>();
        for (Backups.WorldRef w : b.worlds()) out.add(w.name);
        return out;
    }

    private Manifest find(List<String> rest) throws IOException {
        if (rest.isEmpty()) throw new IOException("Which snapshot? Give its id (see /worldbackup list).");
        String id = String.join(" ", rest);
        Manifest m = b.store().find(id);
        if (m == null) throw new IOException("No snapshot " + id);
        return m;
    }

    private static CompletableFuture<List<String>> done(String line) {
        return CompletableFuture.completedFuture(List.of(line));
    }
}
