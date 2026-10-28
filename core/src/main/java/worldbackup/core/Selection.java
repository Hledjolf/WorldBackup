package worldbackup.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Which files under a world's root belong to a snapshot, from a game profile: {@code include} and
 * {@code exclude} globs, and {@code volatile} globs for files the game rewrites on a timer whatever happens.
 * Globs use '/' separators: {@code *} matches within one path segment, {@code **} across segments,
 * {@code ?} one character. Matching ignores case.
 */
public final class Selection {
    public final List<String> include = new ArrayList<>();
    public final List<String> exclude = new ArrayList<>();
    public final List<String> volatiles = new ArrayList<>();

    public static Selection everything() {
        Selection s = new Selection();
        s.include.add("**");
        return s;
    }

    public boolean selects(String path) {
        if (path.startsWith(RestoreJob.RESTORE_DIR + "/") || path.startsWith(RestoreJob.OLD_DIR + "/")) return false;
        return matchesAny(include, path) && !matchesAny(exclude, path);
    }

    public boolean isVolatile(String path) {
        return matchesAny(volatiles, path);
    }

    private static boolean matchesAny(List<String> globs, String path) {
        for (String g : globs) {
            if (glob(g, path)) return true;
        }
        return false;
    }

    public static boolean glob(String pattern, String path) {
        return match(pattern.toLowerCase(), 0, path.toLowerCase(), 0);
    }

    private static boolean match(String p, int pi, String s, int si) {
        while (pi < p.length()) {
            char c = p.charAt(pi);
            if (c == '*') {
                boolean deep = pi + 1 < p.length() && p.charAt(pi + 1) == '*';
                int next = pi + (deep ? 2 : 1);
                // "**/" also matches zero segments
                if (deep && next < p.length() && p.charAt(next) == '/' && match(p, next + 1, s, si)) return true;
                for (int k = si; k <= s.length(); k++) {
                    if (match(p, next, s, k)) return true;
                    if (k < s.length() && !deep && s.charAt(k) == '/') return false;
                }
                return false;
            }
            if (si >= s.length()) return false;
            if (c != '?' && c != s.charAt(si)) return false;
            if (c == '?' && s.charAt(si) == '/') return false;
            pi++;
            si++;
        }
        return si == s.length();
    }
}
