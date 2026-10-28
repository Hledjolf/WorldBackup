package worldbackup.core;

import java.io.IOException;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;

/**
 * Checks that every block a snapshot needs is in the store, has the right size and still matches its hash,
 * and that each file's blocks add up to its whole-file hash where the manifest records one.
 */
public final class Verify {
    private Verify() {
    }

    /** The first problem found, or null when the snapshot is whole. Reads only; safe without the lock. */
    public static String firstProblem(Store store, Manifest m) throws IOException {
        return check(store, m, null);
    }

    /**
     * Like {@link #firstProblem}, but deletes every damaged or wrong-sized block it finds, so the next snapshot
     * of a file that still has that content writes the block again. Runs under the store lock.
     */
    public static String heal(StoreLock lock, Store store, Manifest m) throws IOException {
        if (lock == null) throw new IllegalArgumentException("Healing runs under the store lock");
        return check(store, m, lock);
    }

    private static String check(Store store, Manifest m, StoreLock healing) throws IOException {
        MessageDigest md = FileListing.newDigest();
        MessageDigest whole = FileListing.newDigest();
        Map<String, String> verdict = new HashMap<>(); // block -> problem, or "" when good
        String first = null;
        for (Manifest.FileEntry f : m.files) {
            whole.reset();
            String fileProblem = null;
            for (int i = 0; i < f.blocks.size(); i++) {
                String b = f.blocks.get(i);
                long want = f.blockLength(i);
                String v = verdict.get(b);
                byte[] data = null;
                if (v == null) {
                    if (!store.hasBlock(b)) {
                        v = "block " + b + " is missing";
                    } else {
                        data = store.readBlock(b);
                        md.reset();
                        if (data.length != want) v = "block " + b + " is " + data.length + " bytes, not " + want;
                        else if (!FileListing.hex(md.digest(data)).equals(b)) v = "block " + b + " is damaged";
                        else v = "";
                        if (!v.isEmpty() && !v.endsWith("missing") && healing != null) store.deleteBlock(b);
                    }
                    verdict.put(b, v);
                }
                if (!v.isEmpty()) {
                    if (fileProblem == null) fileProblem = v;
                    continue;
                }
                if (f.sha256 != null) whole.update(data != null ? data : store.readBlock(b));
            }
            if (fileProblem == null && f.sha256 != null && !FileListing.hex(whole.digest()).equals(f.sha256)) {
                fileProblem = "the blocks don't add up to the file's hash";
            }
            if (fileProblem != null && first == null) {
                first = f.path + ": " + fileProblem;
                if (healing == null) return first;
            }
        }
        return first;
    }
}
