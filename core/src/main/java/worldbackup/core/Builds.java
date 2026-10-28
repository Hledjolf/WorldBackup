package worldbackup.core;

/**
 * Compares game builds such as {@code 0.7.4} and {@code 0.10.0-pre2}: numeric runs numerically, the rest as
 * text, and a pre-release ({@code -pre2}, {@code -rc1}) older than the release it leads to.
 */
public final class Builds {
    private Builds() {
    }

    /** Negative when {@code a} is older than {@code b}, 0 when the same, positive when newer. Null sorts oldest. */
    public static int compare(String a, String b) {
        if (a == null || b == null) return a == null ? (b == null ? 0 : -1) : 1;
        int i = 0, j = 0;
        while (i < a.length() && j < b.length()) {
            char ca = a.charAt(i), cb = b.charAt(j);
            if (Character.isDigit(ca) && Character.isDigit(cb)) {
                int si = i, sj = j;
                while (i < a.length() && Character.isDigit(a.charAt(i))) i++;
                while (j < b.length() && Character.isDigit(b.charAt(j))) j++;
                String na = strip(a.substring(si, i)), nb = strip(b.substring(sj, j));
                if (na.length() != nb.length()) return na.length() - nb.length();
                int c = na.compareTo(nb);
                if (c != 0) return c;
            } else {
                if (ca != cb) return ca - cb;
                i++;
                j++;
            }
        }
        boolean aLeft = i < a.length(), bLeft = j < b.length();
        if (aLeft && !bLeft) return a.charAt(i) == '-' ? -1 : 1;
        if (bLeft && !aLeft) return b.charAt(j) == '-' ? 1 : -1;
        return 0;
    }

    private static String strip(String digits) {
        int k = 0;
        while (k < digits.length() - 1 && digits.charAt(k) == '0') k++;
        return digits.substring(k);
    }
}
