package worldbackup.app;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import worldbackup.core.Selection;

/** Which files make up one world's snapshot, per game (design/Hytale.md, "Where worlds live"). */
public final class Profiles {
    public static final String HYTALE = "hytale";

    private Profiles() {
    }

    /** The root's game files: everything else there (server jars, start scripts, logs) is never copied or touched. */
    static final List<String> HYTALE_ROOT_FILES = List.of("config.json", "permissions.json", "whitelist.json",
        "whitelist.json.migrated", "bans.json", "client_metadata.json", "preview.png");

    /**
     * Hytale: the root's game files, {@code universe/} with this world's folder but no other folder under
     * {@code universe/worlds/} (listed or not as a world by the game), and the plugins' data folders under
     * {@code mods/}; without the {@code .bak} copies the game rewrites with each JSON, the world lock, and
     * WorldBackup's own folder. An allow list, because a restore moves away every selected file the snapshot
     * doesn't have.
     *
     * @param worldFolders every folder under {@code universe/worlds/}, '/'-separated from the root
     * @param pluginDataFolders the loaded plugins' data folders ({@code mods/Group_Name}); null when the game
     *     can't say (the command line), and then every folder under {@code mods/} without a manifest.json
     *     (an asset pack has one, a data folder doesn't), from {@code root}
     */
    public static Selection hytale(String worldFolder, List<String> worldFolders, String dataFolder, boolean includePlayers,
                                   List<String> pluginDataFolders) {
        Selection s = new Selection();
        s.include.addAll(HYTALE_ROOT_FILES);
        s.include.add("universe/**");
        for (String f : pluginDataFolders) s.include.add(f + "/**");
        s.exclude.add("**/.world.lock");
        s.exclude.add("**/*.bak");
        s.exclude.add(dataFolder + "/**");
        for (String other : worldFolders) {
            if (!other.equalsIgnoreCase(worldFolder)) s.exclude.add(other + "/**");
        }
        if (!includePlayers) s.exclude.add("universe/players/**");
        s.volatiles.add("config.json");
        s.volatiles.add(worldFolder + "/config.json");
        s.volatiles.add(worldFolder + "/resources/*.json");
        s.volatiles.add("universe/resources/*.json");
        return s;
    }

    /** Every folder under {@code universe/worlds/}, '/'-separated from the root. */
    public static List<String> worldFoldersOnDisk(Path root) {
        return foldersUnder(root, "universe/worlds", false);
    }

    /** Every folder under {@code mods/} that isn't an unpacked asset pack (no manifest.json). */
    public static List<String> dataFoldersOnDisk(Path root) {
        return foldersUnder(root, "mods", true);
    }

    private static List<String> foldersUnder(Path root, String rel, boolean skipPacks) {
        List<String> out = new ArrayList<>();
        Path dir = root.resolve(rel);
        if (!Files.isDirectory(dir)) return out;
        try (Stream<Path> s = Files.list(dir)) {
            s.filter(Files::isDirectory)
                .filter(p -> !skipPacks || !Files.exists(p.resolve("manifest.json")))
                .forEach(p -> out.add(rel + "/" + p.getFileName()));
        } catch (IOException e) {
            return out;
        }
        out.sort(null);
        return out;
    }
}
