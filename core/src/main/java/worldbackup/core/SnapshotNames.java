package worldbackup.core;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Snapshots are named after the world as the player knows it, plus a timestamp and the trigger, never after a
 * game's internal world name: {@code Dimrill Dale_20261024-152825_scheduled}.
 */
public final class SnapshotNames {
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private SnapshotNames() {
    }

    /**
     * The world's name: the save's or server's folder name, plus the game's world name when that isn't the
     * game's default one ({@code "MyServer - arena"}).
     */
    public static String worldName(String sourceFolder, String gameWorld, String gameDefaultWorld) {
        if (gameWorld == null || gameWorld.isEmpty() || gameWorld.equalsIgnoreCase(gameDefaultWorld)) return sourceFolder;
        return sourceFolder + " - " + gameWorld;
    }

    public static String id(String world, LocalDateTime when, Trigger trigger) {
        return folder(world) + "_" + STAMP.format(when) + "_" + trigger.id;
    }

    /** {@code name} made safe as a file or folder name on Windows, macOS and Linux. */
    public static String folder(String name) {
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean bad = c < 0x20 || "<>:\"/\\|?*".indexOf(c) >= 0;
            sb.append(bad ? '_' : c);
        }
        String s = sb.toString().trim();
        while (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        if (s.isEmpty()) s = "_";
        String upper = s.toUpperCase();
        int dot = upper.indexOf('.');
        String stem = dot < 0 ? upper : upper.substring(0, dot);
        if (stem.matches("CON|PRN|AUX|NUL|COM[0-9]|LPT[0-9]")) s = "_" + s;
        return s;
    }
}
