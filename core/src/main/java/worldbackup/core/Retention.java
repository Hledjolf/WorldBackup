package worldbackup.core;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pruning and garbage collection (design/settings.md, "Retention, with these settings").
 *
 * <ol>
 *   <li>Per world with a known policy: scheduled snapshots thinned with age (the newest {@code keepScheduled},
 *       then the last of each day for {@code keepDaily} days, then the last of each week for {@code keepWeekly}
 *       weeks), shutdown beyond {@code keepShutdown}, and automatic milestones beyond {@code keepMilestones}
 *       (0 = all), oldest first.</li>
 *   <li>While the store is over the machine-wide cap: the oldest unprotected snapshot in any world, scheduled
 *       and shutdown first, then the rest, then those of worlds whose folder is gone.</li>
 *   <li>Garbage collection: every block no remaining manifest uses, and leftover temp files.</li>
 * </ol>
 *
 * Protected, never deleted here: pinned snapshots, the latest {@code pre-restore} per world, snapshots staged
 * for a restore on any server (from the source files, plus the caller's own), and automatic milestones within
 * {@code keepMilestones} (all of them for a world whose policy isn't known, since another server owns it;
 * pinned ones don't take a slot). Garbage collection deletes no block at all while any manifest can't be read,
 * since it can't know which blocks that manifest needs. When only protected snapshots remain over the cap, nothing more is deleted and the result
 * says so. A world whose source folder is gone ({@link SourceFile#gone()}) is never pruned by count, since
 * nothing new will replace its snapshots.
 */
public final class Retention {
    public static final class Result {
        public final List<Manifest> removed = new ArrayList<>();
        public long bytesFreed;
        public long totalBytes;
        public long capBytes;
        public boolean overCapProtectedOnly;
        /** Manifests that couldn't be read ({@link Store.Scan#unreadable}); while there are any, no block is freed. */
        public final List<String> unreadable = new ArrayList<>();
    }

    private final Store store;

    public Retention(Store store) {
        this.store = store;
    }

    public Result prune(StoreLock lock, Map<String, RetentionPolicy> policies, StoreSettings settings) throws IOException {
        return prune(lock, policies, settings, Set.of());
    }

    /**
     * {@code policies}: the worlds whose counts this server knows. Others take part only in the cap step.
     * {@code staged}: snapshot ids this server has staged for a restore (other servers' come from their source
     * files).
     */
    public Result prune(StoreLock lock, Map<String, RetentionPolicy> policies, StoreSettings settings, Set<String> staged) throws IOException {
        if (lock == null) throw new IllegalArgumentException("Pruning runs under the store lock");
        Usage u = Usage.measure(store);
        Result res = new Result();
        res.capBytes = settings.maxTotalBytes();
        long before = u.total;

        Set<String> gone = new HashSet<>();
        Set<String> stagedIds = new HashSet<>(staged);
        for (SourceFile s : store.sources()) {
            if (s.gone()) gone.add(s.world);
            if (s.pendingRestore != null) stagedIds.add(s.pendingRestore);
        }
        Map<String, List<Manifest>> byWorld = new HashMap<>();
        for (Manifest m : u.manifests) byWorld.computeIfAbsent(m.world, k -> new ArrayList<>()).add(m);

        // 1. counts per world
        Set<Manifest> doomed = new HashSet<>();
        for (Map.Entry<String, List<Manifest>> e : byWorld.entrySet()) {
            RetentionPolicy p = policies.get(e.getKey());
            if (p == null || gone.contains(e.getKey())) continue;
            thinScheduled(e.getValue(), p, doomed);
            overCount(e.getValue(), Trigger.SHUTDOWN, p.keepShutdown, doomed);
            if (p.keepMilestones > 0) overMilestones(e.getValue(), p.keepMilestones, doomed);
        }
        doomed.removeIf(m -> stagedIds.contains(m.id));
        for (Manifest m : u.manifests) {
            if (doomed.contains(m)) u.drop(m);
        }

        // 2. the cap, oldest unprotected first: scheduled and shutdown, then the rest, then gone worlds'
        Set<Manifest> protectedSet = protectedSnapshots(byWorld, policies);
        for (Manifest m : u.manifests) {
            if (stagedIds.contains(m.id)) protectedSet.add(m);
        }
        for (int tier = 0; tier < 3 && u.total > res.capBytes; tier++) {
            for (Manifest m : u.manifests) { // oldest first
                if (u.total <= res.capBytes) break;
                if (doomed.contains(m) || protectedSet.contains(m)) continue;
                boolean rolling = m.trigger == Trigger.SCHEDULED || m.trigger == Trigger.SHUTDOWN;
                int t = gone.contains(m.world) ? 2 : rolling ? 0 : 1;
                if (t != tier) continue;
                doomed.add(m);
                u.drop(m);
            }
        }
        res.overCapProtectedOnly = u.total > res.capBytes;

        // delete manifests first (a snapshot stops existing), then the blocks nobody uses
        for (Manifest m : u.manifests) {
            if (!doomed.contains(m)) continue;
            store.deleteManifest(m);
            res.removed.add(m);
        }
        res.unreadable.addAll(collectGarbage(lock).unreadable);
        res.totalBytes = Usage.measure(store).total;
        res.bytesFreed = before - res.totalBytes;
        return res;
    }

    /** What garbage collection did: bytes freed, and the manifests that kept it from freeing anything. */
    public static final class Garbage {
        public long freed;
        public final List<String> unreadable = new ArrayList<>();
    }

    /**
     * Deletes every block no manifest uses, and temp files left by a crash. While any manifest can't be read,
     * it deletes no block at all.
     */
    public Garbage collectGarbage(StoreLock lock) throws IOException {
        if (lock == null) throw new IllegalArgumentException("Garbage collection runs under the store lock");
        Garbage g = new Garbage();
        Store.Scan scan = store.scan();
        store.clearManifestTemp(scan);
        store.clearBlockTemp();
        if (!scan.unreadable.isEmpty()) {
            g.unreadable.addAll(scan.unreadable);
            return g;
        }
        Set<String> used = new HashSet<>();
        for (Manifest m : scan.manifests) {
            for (Manifest.FileEntry f : m.files) used.addAll(f.blocks);
        }
        long freed = 0;
        for (Map.Entry<String, Long> b : store.allBlocks().entrySet()) {
            if (used.contains(b.getKey())) continue;
            store.deleteBlock(b.getKey());
            freed += b.getValue();
        }
        g.freed = freed;
        return g;
    }

    public static Set<Manifest> protectedSnapshots(Map<String, List<Manifest>> byWorld, Map<String, RetentionPolicy> policies) {
        Set<Manifest> out = new HashSet<>();
        for (Map.Entry<String, List<Manifest>> e : byWorld.entrySet()) {
            List<Manifest> ms = e.getValue(); // oldest first
            RetentionPolicy p = policies.get(e.getKey());
            int keepMilestones = p == null ? 0 : p.keepMilestones;
            Manifest lastPreRestore = null;
            List<Manifest> milestones = new ArrayList<>();
            for (Manifest m : ms) {
                if (m.pinned) out.add(m);
                if (m.trigger == Trigger.PRE_RESTORE) lastPreRestore = m;
                if (m.trigger.autoMilestone && !m.pinned) milestones.add(m);
            }
            if (lastPreRestore != null) out.add(lastPreRestore);
            int from = keepMilestones == 0 ? 0 : Math.max(0, milestones.size() - keepMilestones);
            out.addAll(milestones.subList(from, milestones.size()));
        }
        return out;
    }

    /**
     * Days and weeks are the local calendar's, read from each snapshot's {@code created} time; a day or week
     * with no snapshot uses no slot.
     */
    static void thinScheduled(List<Manifest> ms, RetentionPolicy p, Set<Manifest> doomed) {
        List<Manifest> of = new ArrayList<>();
        for (int i = ms.size() - 1; i >= 0; i--) { // newest first
            Manifest m = ms.get(i);
            if (m.trigger == Trigger.SCHEDULED && !m.pinned) of.add(m);
        }
        List<Manifest> rest = of.subList(Math.min(p.keepScheduled, of.size()), of.size());
        rest = lastOfEach(rest, p.keepDaily, m -> m.localDate().toString(), doomed);
        rest = lastOfEach(rest, p.keepWeekly, m -> {
            java.time.LocalDate d = m.localDate();
            return d.get(java.time.temporal.IsoFields.WEEK_BASED_YEAR) + "-W" + d.get(java.time.temporal.IsoFields.WEEK_OF_WEEK_BASED_YEAR);
        }, doomed);
        doomed.addAll(rest);
    }

    /**
     * From {@code newestFirst}, keeps the newest snapshot of each of the first {@code periods} periods; the
     * others in those periods are doomed. Returns the snapshots older than those periods.
     */
    private static List<Manifest> lastOfEach(List<Manifest> newestFirst, int periods, java.util.function.Function<Manifest, String> period,
                                             Set<Manifest> doomed) {
        String current = null;
        int seen = 0;
        for (int i = 0; i < newestFirst.size(); i++) {
            Manifest m = newestFirst.get(i);
            String key = period.apply(m);
            if (key.equals(current)) {
                doomed.add(m);
                continue;
            }
            if (seen == periods) return newestFirst.subList(i, newestFirst.size());
            current = key;
            seen++;
        }
        return newestFirst.subList(newestFirst.size(), newestFirst.size());
    }

    private static void overCount(List<Manifest> ms, Trigger t, int keep, Set<Manifest> doomed) {
        List<Manifest> of = new ArrayList<>();
        for (Manifest m : ms) {
            if (m.trigger == t && !m.pinned) of.add(m);
        }
        for (int i = 0; i < of.size() - keep; i++) doomed.add(of.get(i));
    }

    private static void overMilestones(List<Manifest> ms, int keep, Set<Manifest> doomed) {
        List<Manifest> of = new ArrayList<>();
        for (Manifest m : ms) {
            if (m.trigger.autoMilestone && !m.pinned) of.add(m);
        }
        for (int i = 0; i < of.size() - keep; i++) doomed.add(of.get(i));
    }

    /**
     * What the store holds, with a reference count per block, so dropping a snapshot (in memory) tells what
     * it would free. Total = block bytes in use + manifest files.
     */
    public static final class Usage {
        public final List<Manifest> manifests;
        public final Map<String, Long> blockSizes;
        private final Map<String, Integer> refs = new HashMap<>();
        private final Map<Manifest, Long> manifestBytes = new HashMap<>();
        public long total;

        private Usage(List<Manifest> manifests, Map<String, Long> blockSizes) {
            this.manifests = manifests;
            this.blockSizes = blockSizes;
        }

        public static Usage measure(Store store) throws IOException {
            Usage u = new Usage(store.allManifests(), store.allBlocks());
            for (Manifest m : u.manifests) {
                for (String b : distinctBlocks(m)) u.refs.merge(b, 1, Integer::sum);
                long mb = Files.exists(store.manifestPath(m)) ? Files.size(store.manifestPath(m)) : 0;
                u.manifestBytes.put(m, mb);
                u.total += mb;
            }
            for (Map.Entry<String, Integer> r : u.refs.entrySet()) {
                Long size = u.blockSizes.get(r.getKey());
                if (size != null) u.total += size;
            }
            return u;
        }

        /** Bytes that deleting {@code m} would free now: blocks only it uses, plus its manifest. */
        public long uniqueBytes(Manifest m) {
            long t = manifestBytes.getOrDefault(m, 0L);
            for (String b : distinctBlocks(m)) {
                if (refs.getOrDefault(b, 0) == 1) t += blockSizes.getOrDefault(b, 0L);
            }
            return t;
        }

        void drop(Manifest m) {
            if (!manifestBytes.containsKey(m)) return;
            total -= manifestBytes.remove(m);
            for (String b : distinctBlocks(m)) {
                int n = refs.getOrDefault(b, 0) - 1;
                if (n <= 0) {
                    refs.remove(b);
                    total -= blockSizes.getOrDefault(b, 0L);
                } else {
                    refs.put(b, n);
                }
            }
        }

        private static Set<String> distinctBlocks(Manifest m) {
            Set<String> s = new HashSet<>();
            for (Manifest.FileEntry f : m.files) s.addAll(f.blocks);
            return s;
        }
    }
}
