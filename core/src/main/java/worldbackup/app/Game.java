package worldbackup.app;

import java.nio.file.Path;
import java.util.List;
import worldbackup.core.BootCheck;

/**
 * What {@link Backups} needs from the game: the adapter implements it with the game's API, tests with a fake.
 * Calls may come from any thread; the adapter moves work onto the game's threads where it must.
 */
public interface Game {
    /** {@code hytale}. */
    String id();

    /** The server root, or the singleplayer save's folder (a full server root in Hytale). */
    Path root();

    /** WorldBackup's own folder for this server: its config.json and per-server state. */
    Path dataDir();

    /** True in singleplayer, where the plugin runs inside one save's built-in server. */
    boolean singleplayer();

    String build();

    String revision();

    String patchline();

    /** WorldBackup's own version. */
    String modVersion();

    /** The game's world names (Hytale: the folders in {@code universe/worlds/}), loaded or not. */
    List<String> worlds();

    /** The game's default world name ({@code default} in Hytale), which isn't added to the world's name. */
    String defaultWorld();

    /** The world folder of a game world, relative to {@link #root()}, '/'-separated. */
    String worldFolder(String gameWorld);

    /** The mods installed now: id, version and jar hash. */
    List<BootCheck.Mod> mods();

    /**
     * Every loaded plugin's data folder relative to {@link #root()}, '/'-separated ({@code mods/Group_Name}),
     * whether or not it exists yet: what a snapshot takes from {@code mods/}. Null when the game can't say.
     */
    default List<String> pluginDataFolders() {
        return null;
    }

    /**
     * Saves a loaded world to disk and waits for it, so a snapshot sees what players see. Does nothing for a
     * world that isn't loaded (in setup(), or after it's been removed).
     */
    void saveWorld(String gameWorld) throws Exception;

    int playersOnline();

    /** The game's own backups: size in bytes and file count of their folder, or null when there are none. */
    long[] builtInBackups();

    boolean builtInBackupsEnabled();

    /** Asks Mod Settings Manager, if it's installed, to read the sidecars again. */
    default void reloadSettingsWindow() {
    }

    void info(String message);

    void warn(String message);

    /** A chat line to operators (and the singleplayer owner) online now. */
    void tellOps(String message);
}
