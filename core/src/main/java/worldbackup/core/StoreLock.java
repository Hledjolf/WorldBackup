package worldbackup.core;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The store's one lock. A process that dies loses its OS lock with it, so a crashed server never leaves the
 * store locked. It holds across processes (an OS file lock on {@code <store>/lock}) and across threads in this
 * process. Held for a whole snapshot, prune or restore build, so a second server simply waits: the lock is
 * also the queue. Use with try-with-resources.
 */
public final class StoreLock implements AutoCloseable {
    private static final Map<String, ReentrantLock> IN_PROCESS = new HashMap<>();

    private final ReentrantLock local;
    private final FileChannel channel;
    private final FileLock lock;

    private StoreLock(ReentrantLock local, FileChannel channel, FileLock lock) {
        this.local = local;
        this.channel = channel;
        this.lock = lock;
    }

    /** Waits up to {@code timeoutMillis} for the lock; throws {@link IOException} if it doesn't come. */
    public static StoreLock acquire(Store store, long timeoutMillis) throws IOException {
        Files.createDirectories(store.root());
        Path file = store.root().resolve("lock");
        String key = file.toAbsolutePath().normalize().toString().toLowerCase();
        ReentrantLock local;
        synchronized (IN_PROCESS) {
            local = IN_PROCESS.computeIfAbsent(key, k -> new ReentrantLock());
        }
        long deadline = System.currentTimeMillis() + timeoutMillis;
        try {
            if (!local.tryLock(timeoutMillis, TimeUnit.MILLISECONDS)) throw busy(store);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for the backup store lock", e);
        }
        FileChannel ch = null;
        try {
            ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            while (true) {
                FileLock l = ch.tryLock();
                if (l != null) {
                    if (store.readOnly()) {
                        l.release(); // the catch below closes the channel and the in-process hold
                        throw new IOException("The backup folder " + store.root()
                            + " was written by a newer WorldBackup. Update to back up here.");
                    }
                    return new StoreLock(local, ch, l);
                }
                if (System.currentTimeMillis() >= deadline) throw busy(store);
                Thread.sleep(200);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            closeQuietly(ch);
            local.unlock();
            throw new IOException("Interrupted while waiting for the backup store lock", e);
        } catch (IOException | RuntimeException e) {
            closeQuietly(ch);
            local.unlock();
            throw e;
        }
    }

    private static IOException busy(Store store) {
        return new IOException("The backup store at " + store.root() + " is busy (another server is writing to it)");
    }

    private static void closeQuietly(FileChannel ch) {
        if (ch == null) return;
        try {
            ch.close();
        } catch (IOException ignored) {
        }
    }

    @Override
    public void close() throws IOException {
        try {
            lock.release();
            channel.close();
        } finally {
            local.unlock();
        }
    }
}
