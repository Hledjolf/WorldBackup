package worldbackup.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Every regular file under a folder: relative path ('/'-separated), size, modification time and, optionally,
 * SHA-256. Sorted by path. Saved as tab-separated lines so a person can read it.
 */
public final class FileListing {
    public static final class Entry {
        public final String path;
        public final long size;
        public final long mtimeMillis;
        public final String sha256; // null when the listing was taken without hashes

        public Entry(String path, long size, long mtimeMillis, String sha256) {
            this.path = path;
            this.size = size;
            this.mtimeMillis = mtimeMillis;
            this.sha256 = sha256;
        }
    }

    private final TreeMap<String, Entry> entries = new TreeMap<>();

    public Map<String, Entry> entries() {
        return entries;
    }

    public long totalBytes() {
        long total = 0;
        for (Entry e : entries.values()) total += e.size;
        return total;
    }

    /** Lists {@code root}. A missing root gives an empty listing. */
    public static FileListing scan(Path root, boolean hash) throws IOException {
        FileListing listing = new FileListing();
        if (!Files.isDirectory(root)) return listing;
        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile).forEach(files::add);
        }
        for (Path f : files) {
            String rel = root.relativize(f).toString().replace('\\', '/');
            long size;
            long mtime;
            try {
                size = Files.size(f);
                mtime = Files.getLastModifiedTime(f).toMillis();
            } catch (IOException gone) {
                continue; // deleted while listing
            }
            listing.entries.put(rel, new Entry(rel, size, mtime, hash ? sha256(f) : null));
        }
        return listing;
    }

    public static String sha256(Path file) throws IOException {
        MessageDigest md = newDigest();
        byte[] buf = new byte[1 << 16];
        try (InputStream in = Files.newInputStream(file)) {
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        return hex(md.digest());
    }

    public static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 missing from this Java runtime", e);
        }
    }

    public static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    /**
     * What changed from {@code before} to {@code after}, one line per file: {@code + path} added,
     * {@code - path} removed, {@code ~ path [size] [mtime] [hash]} changed. Hashes are compared only when both
     * sides have them. Empty when nothing changed.
     */
    public static List<String> diff(FileListing before, FileListing after) {
        List<String> out = new ArrayList<>();
        for (Entry b : before.entries.values()) {
            Entry a = after.entries.get(b.path);
            if (a == null) {
                out.add("- " + b.path);
                continue;
            }
            StringBuilder what = new StringBuilder();
            if (a.size != b.size) what.append(" size");
            if (a.mtimeMillis != b.mtimeMillis) what.append(" mtime");
            if (a.sha256 != null && b.sha256 != null && !a.sha256.equals(b.sha256)) what.append(" hash");
            if (what.length() > 0) out.add("~ " + b.path + what);
        }
        for (Entry a : after.entries.values()) {
            if (!before.entries.containsKey(a.path)) out.add("+ " + a.path);
        }
        return out;
    }

    public void write(Path file) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (Entry e : entries.values()) {
            sb.append(e.path).append('\t').append(e.size).append('\t').append(e.mtimeMillis).append('\t')
                .append(e.sha256 == null ? "-" : e.sha256).append('\n');
        }
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
    }

    public static FileListing read(Path file) throws IOException {
        FileListing listing = new FileListing();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isEmpty()) continue;
            String[] f = line.split("\t", -1);
            if (f.length != 4) throw new IOException("Bad listing line in " + file + ": " + line);
            String hash = f[3].equals("-") ? null : f[3];
            listing.entries.put(f[0], new Entry(f[0], Long.parseLong(f[1]), Long.parseLong(f[2]), hash));
        }
        return listing;
    }
}
