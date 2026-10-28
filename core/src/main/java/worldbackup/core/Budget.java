package worldbackup.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The budget check's arithmetic (design/settings.md, "The budget check"): do these settings fit in this space?
 * Pure: the caller measures the worlds and passes the numbers in, so it's easy to test and to port.
 */
public final class Budget {
    public enum Level { ERROR, WARNING, INFO }

    public static final class Problem {
        public final Level level;
        public final String message;

        Problem(Level level, String message) {
            this.level = level;
            this.message = message;
        }

        @Override
        public String toString() {
            return level + ": " + message;
        }
    }

    /** One world's measurements. */
    public static final class World {
        public String name;
        /** This world's counts when they differ from the server's (a per-world override); null: {@link Input#policy}. */
        public RetentionPolicy policy;
        public long sizeBytes;
        /** {@code addedBytes} of recent scheduled snapshots, oldest first (the last 10 are used). */
        public List<Long> churnSamples = new ArrayList<>();
        public List<Long> tookMillisSamples = new ArrayList<>();
        /** Bytes only protected snapshots hold (would be freed if they were deleted). */
        public long protectedBytes;
        public long milestoneBytes;
        public int milestoneCount;
        public boolean updatedRecently;
    }

    public static final class Input {
        public final List<World> worlds = new ArrayList<>();
        public RetentionPolicy policy = new RetentionPolicy();
        public boolean scheduled = true;
        public int scheduleMinutes = 60;
        /** {@code MaxCopyMBps}; 0 means no limit. */
        public int maxCopyMBps = 100;
        public long capBytes = 10000L * 1024 * 1024;
        public long storeBytes;
        public long freeBytes = Long.MAX_VALUE;
        public long builtinBackupBytes = -1; // -1: none found
    }

    public static final class Report {
        public long projectedBytes;
        /** How far back the settings reach, for the world that reaches furthest. */
        public long historyMinutes;
        /** How far back the snapshots that fit in the cap reach, for that world. */
        public long effectiveHistoryMinutes;
        public final List<Problem> problems = new ArrayList<>();
        public String info;

        public Level worst() {
            Level w = Level.INFO;
            for (Problem p : problems) {
                if (p.level.ordinal() < w.ordinal()) w = p.level;
            }
            return w;
        }
    }

    static final long MB = 1024L * 1024;
    static final double GUESS_CHURN = 0.05;
    static final long MAX_GUESS_MBPS = 100;
    static final long HEAVY_BYTES_PER_MINUTE = 50 * MB;
    static final long DAY = 24 * 60, WEEK = 7 * DAY;

    private Budget() {
    }

    public static Report check(Input in) {
        Report r = new Report();
        long totalProtected = 0, fixed = 0, totalMilestones = 0;
        int milestoneCount = 0, maxCount = 0;
        boolean guessed = false, updated = false, anyUnlimitedMilestones = false;

        for (World w : in.worlds) {
            RetentionPolicy p = policy(in, w);
            long churn = churn(w);
            if (w.churnSamples.size() < 3) guessed = true;
            if (w.updatedRecently) updated = true;
            if (p.keepMilestones == 0) anyUnlimitedMilestones = true;
            totalProtected += w.protectedBytes;
            fixed += w.sizeBytes + w.protectedBytes + p.keepShutdown * churn;
            totalMilestones += w.milestoneBytes;
            milestoneCount += w.milestoneCount;
            maxCount = Math.max(maxCount, scheduledCount(in, p));
            r.projectedBytes += w.sizeBytes + (scheduledCount(in, p) + p.keepShutdown) * churn + w.protectedBytes;

            if (w.sizeBytes > in.capBytes) {
                r.problems.add(new Problem(Level.ERROR, "One copy of '" + w.name + "' is " + size(w.sizeBytes)
                    + ", but MaxTotalMB is " + size(in.capBytes) + ". No snapshot of it can be kept."));
            }
            long took = took(w, in.maxCopyMBps);
            long interval = in.scheduleMinutes * 60_000L;
            if (in.scheduled && took > interval) {
                r.problems.add(new Problem(Level.ERROR, "A snapshot of '" + w.name + "' takes about " + minutes(took)
                    + ", but ScheduleMinutes is " + in.scheduleMinutes + "."));
            } else if (in.scheduled && in.maxCopyMBps > 0 && took > interval / 2) {
                r.problems.add(new Problem(Level.WARNING, "At " + in.maxCopyMBps + " MB/s, a snapshot of '" + w.name
                    + "' takes about " + minutes(took) + ", over half of ScheduleMinutes (" + in.scheduleMinutes + ")."));
            }
            if (in.scheduled && churn / Math.max(1, in.scheduleMinutes) > HEAVY_BYTES_PER_MINUTE) {
                r.problems.add(new Problem(Level.WARNING, "Snapshots of '" + w.name + "' copy about " + size(churn)
                    + " every " + in.scheduleMinutes + " minutes."));
            }
        }

        if (totalProtected >= in.capBytes) {
            r.problems.add(new Problem(Level.ERROR, "Pinned snapshots and milestones use " + size(totalProtected) + " of the "
                + size(in.capBytes) + " cap. New snapshots will push out everything else."));
        }

        // how many scheduled snapshots per world fit: the same number for every world, up to each one's count
        int fit = maxCount;
        if (r.projectedBytes > in.capBytes) {
            fit = 0;
            while (fit < maxCount && fixed + scheduledCost(in, fit + 1) <= in.capBytes) fit++;
        }
        List<String> perWorld = new ArrayList<>();
        List<String> shortfalls = new ArrayList<>();
        for (World w : in.worlds) {
            RetentionPolicy p = policy(in, w);
            int n = scheduledCount(in, p);
            long asked = history(p, in.scheduleMinutes, n);
            long got = history(p, in.scheduleMinutes, Math.min(n, fit));
            if (asked >= r.historyMinutes) {
                r.historyMinutes = asked;
                r.effectiveHistoryMinutes = got;
            }
            perWorld.add("'" + w.name + "': " + (in.scheduled ? "about " + duration(asked) + " of history" : "scheduled snapshots off"));
            if (got < asked) shortfalls.add("about " + duration(asked) + " of history for '" + w.name + "' and will get about " + duration(got));
        }
        if (in.worlds.isEmpty() && in.scheduled) {
            r.historyMinutes = r.effectiveHistoryMinutes = history(in.policy, in.scheduleMinutes, scheduledCount(in, in.policy));
        }
        if (!shortfalls.isEmpty()) {
            r.problems.add(new Problem(Level.WARNING, "These settings need about " + size(r.projectedBytes) + "; MaxTotalMB is "
                + size(in.capBytes) + ". You asked for " + String.join("; ", shortfalls) + "."));
        }
        if (in.freeBytes != Long.MAX_VALUE && in.capBytes > in.storeBytes + in.freeBytes) {
            r.problems.add(new Problem(Level.WARNING, "MaxTotalMB is " + size(in.capBytes) + ", but the backup drive has "
                + size(in.freeBytes) + " free. The drive fills first, and MinFreeMB will then stop scheduled snapshots."));
        }
        if (anyUnlimitedMilestones && totalMilestones > in.capBytes / 2) {
            r.problems.add(new Problem(Level.WARNING, "Milestones keep growing with every update and mod change ("
                + size(totalMilestones) + " so far). Consider setting KeepMilestones."));
        }
        if (updated) {
            r.problems.add(new Problem(Level.INFO, "The game updated recently; snapshots will be larger for a while."));
        }
        if (guessed) {
            r.problems.add(new Problem(Level.INFO, "Until a world has 3 scheduled snapshots, its churn is a guess (5% of its size)."));
        }

        StringBuilder info = new StringBuilder();
        info.append("Store: ").append(size(in.storeBytes)).append(" of ").append(size(in.capBytes)).append(", ")
            .append(in.worlds.size()).append(in.worlds.size() == 1 ? " world" : " worlds")
            .append("; these settings need about ").append(size(r.projectedBytes)).append(".");
        if (!perWorld.isEmpty()) info.append(" ").append(String.join(", ", perWorld)).append(".");
        RetentionPolicy p = in.policy;
        info.append(" Update and mod-change snapshots: ").append(p.keepMilestones == 0 ? "all kept" : p.keepMilestones + " kept per world")
            .append(" (").append(milestoneCount).append(" so far, ").append(size(totalMilestones)).append(").");
        if (in.builtinBackupBytes >= 0) {
            info.append(" The game's own backups: ").append(size(in.builtinBackupBytes)).append(" (not WorldBackup's; not counted).");
        }
        r.info = info.toString();
        return r;
    }

    private static RetentionPolicy policy(Input in, World w) {
        return w.policy != null ? w.policy : in.policy;
    }

    private static int scheduledCount(Input in, RetentionPolicy p) {
        return in.scheduled ? p.keepScheduled + p.keepDaily + p.keepWeekly : 0;
    }

    private static long scheduledCost(Input in, int n) {
        long t = 0;
        for (World w : in.worlds) t += Math.min(n, scheduledCount(in, policy(in, w))) * churn(w);
        return t;
    }

    /** How far back {@code n} kept scheduled snapshots reach: every one, then one a day, then one a week. */
    static long history(RetentionPolicy p, int scheduleMinutes, int n) {
        long recent = Math.min(n, p.keepScheduled);
        long daily = Math.min(Math.max(0, n - p.keepScheduled), p.keepDaily);
        long weekly = Math.min(Math.max(0, n - p.keepScheduled - p.keepDaily), p.keepWeekly);
        return recent * scheduleMinutes + daily * DAY + weekly * WEEK;
    }

    static long churn(World w) {
        if (w.churnSamples.isEmpty()) return (long) (w.sizeBytes * GUESS_CHURN);
        return median(last(w.churnSamples, 10));
    }

    static long took(World w, int maxCopyMBps) {
        if (!w.tookMillisSamples.isEmpty()) return median(last(w.tookMillisSamples, 10));
        long mbps = maxCopyMBps <= 0 ? MAX_GUESS_MBPS : Math.min(maxCopyMBps, MAX_GUESS_MBPS);
        return w.sizeBytes * 1000 / (mbps * MB);
    }

    private static List<Long> last(List<Long> xs, int n) {
        return xs.subList(Math.max(0, xs.size() - n), xs.size());
    }

    static long median(List<Long> xs) {
        List<Long> s = new ArrayList<>(xs);
        Collections.sort(s);
        int n = s.size();
        return n % 2 == 1 ? s.get(n / 2) : (s.get(n / 2 - 1) + s.get(n / 2)) / 2;
    }

    public static String size(long bytes) {
        if (bytes >= 1024 * MB) return String.format("%.1f GB", bytes / (1024.0 * MB));
        if (bytes >= MB) return (bytes / MB) + " MB";
        return (bytes / 1024) + " KB";
    }

    static String minutes(long millis) {
        if (millis < 90_000) return Math.max(1, Math.round(millis / 1000.0)) + " s";
        return Math.round(millis / 60000.0) + " minutes";
    }

    public static String duration(long minutes) {
        if (minutes < 120) return minutes + " minutes";
        if (minutes < 2 * DAY) return (minutes / 60) + " hours";
        if (minutes < 2 * WEEK) return (minutes / DAY) + " days";
        return Math.round(minutes / (double) WEEK) + " weeks";
    }
}
