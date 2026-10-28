package worldbackup.hytale;

import com.hypixel.hytale.common.plugin.PluginManifest;
import com.hypixel.hytale.common.util.java.ManifestUtil;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.Options;
import com.hypixel.hytale.server.core.command.system.CommandSender;
import com.hypixel.hytale.server.core.modules.singleplayer.SingleplayerModule;
import com.hypixel.hytale.server.core.permissions.PermissionsModule;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.hypixel.hytale.server.core.plugin.PluginManager;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.component.ChunkSavingSystems;
import com.hypixel.hytale.server.core.universe.system.WorldConfigSaveSystem;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import worldbackup.app.Game;
import worldbackup.app.TextCommands;
import worldbackup.core.BootCheck;
import worldbackup.core.FileListing;

/** {@link Game} on Hytale's server API (0.7.4; calls established by the probe, design/Hytale.md). */
public final class HytaleGame implements Game {
    private final JavaPlugin plugin;
    private final HytaleLogger logger;
    private final Path root;
    private final Path universe;
    private final Map<Path, String[]> jarHashes = new HashMap<>();

    public HytaleGame(JavaPlugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        Path u;
        try {
            u = Options.getOrDefault(Options.UNIVERSE, Options.getOptionSet(), Path.of("universe"));
        } catch (Throwable t) {
            u = Path.of("universe");
        }
        this.universe = u.toAbsolutePath().normalize();
        // the root holds config.json, permissions.json and mods/: the universe's parent when it has the usual
        // name (singleplayer saves, default servers), otherwise the server's working folder
        boolean usual = universe.getFileName() != null && universe.getFileName().toString().equalsIgnoreCase("universe");
        this.root = usual ? universe.getParent() : Path.of("").toAbsolutePath().normalize();
    }

    @Override public String id() { return "hytale"; }
    @Override public Path root() { return root; }
    @Override public Path dataDir() { return plugin.getDataDirectory().toAbsolutePath().normalize(); }

    @Override
    public boolean singleplayer() {
        try {
            return Options.getOptionSet().has(Options.SINGLEPLAYER);
        } catch (Throwable t) {
            return false;
        }
    }

    @Override public String build() { return ManifestUtil.getVersion(); }
    @Override public String revision() { return ManifestUtil.getImplementationRevisionId(); }
    @Override public String patchline() { return ManifestUtil.getPatchline(); }
    @Override public String modVersion() { return String.valueOf(plugin.getManifest().getVersion()); }

    /** The world folders on disk: in setup() no world is loaded yet, so the folders are the truth. */
    @Override
    public List<String> worlds() {
        List<String> out = new ArrayList<>();
        Path worlds = universe.resolve("worlds");
        if (!Files.isDirectory(worlds)) return out;
        try (Stream<Path> s = Files.list(worlds)) {
            s.filter(p -> Files.isRegularFile(p.resolve("config.json"))).forEach(p -> out.add(p.getFileName().toString()));
        } catch (IOException e) {
            warn("Couldn't list " + worlds + ": " + e.getMessage());
        }
        out.sort(String::compareToIgnoreCase);
        return out;
    }

    @Override public String defaultWorld() { return "default"; }

    @Override
    public String worldFolder(String gameWorld) {
        return root.relativize(universe.resolve("worlds").resolve(gameWorld)).toString().replace('\\', '/');
    }

    /** Every loaded plugin's data folder, Hytale's own modules included (their data is world data too). */
    @Override
    public List<String> pluginDataFolders() {
        List<String> out = new ArrayList<>();
        for (PluginBase p : PluginManager.get().getPlugins()) {
            Path d = p.getDataDirectory();
            if (d == null) continue;
            d = d.toAbsolutePath().normalize();
            if (d.startsWith(root) && !d.equals(root)) out.add(root.relativize(d).toString().replace('\\', '/'));
        }
        out.sort(null);
        return out;
    }

    /** Every loaded plugin outside Hytale's own group, with its jar's hash (cached by path, size and time). */
    @Override
    public List<BootCheck.Mod> mods() {
        List<BootCheck.Mod> out = new ArrayList<>();
        for (PluginBase p : PluginManager.get().getPlugins()) {
            PluginManifest m = p.getManifest();
            if (PluginManifest.CORE_GROUP.equals(m.getGroup())) continue;
            String hash = p instanceof JavaPlugin jp && jp.getFile() != null ? hash(jp.getFile()) : null;
            out.add(new BootCheck.Mod(m.getGroup() + ":" + m.getName(), String.valueOf(m.getVersion()), hash));
        }
        out.sort((a, b) -> a.id.compareToIgnoreCase(b.id));
        return out;
    }

    private String hash(Path jar) {
        try {
            String stamp = Files.size(jar) + "/" + Files.getLastModifiedTime(jar).toMillis();
            String[] cached = jarHashes.get(jar);
            if (cached != null && cached[0].equals(stamp)) return cached[1];
            MessageDigest md = FileListing.newDigest();
            try (InputStream in = Files.newInputStream(jar)) {
                byte[] buf = new byte[1 << 16];
                for (int n; (n = in.read(buf)) > 0; ) md.update(buf, 0, n);
            }
            String h = FileListing.hex(md.digest());
            jarHashes.put(jar, new String[] {stamp, h});
            return h;
        } catch (IOException e) {
            return null;
        }
    }

    /** The same calls as {@code /world save}, on the world's thread; waits up to a minute. */
    @Override
    public void saveWorld(String gameWorld) throws Exception {
        Universe u = Universe.get();
        World w = u == null ? null : u.getWorld(gameWorld);
        if (w == null || !w.isAlive()) return;
        CompletableFuture<Void> f = CompletableFuture.supplyAsync(() -> CompletableFuture.allOf(
            WorldConfigSaveSystem.saveWorldConfigAndResources(w),
            ChunkSavingSystems.saveChunksInWorld(w.getChunkStore().getStore(), w)), w).thenCompose(x -> x);
        if (w.isInThread()) throw new IllegalStateException("saveWorld called on the world's own thread");
        f.get(60, TimeUnit.SECONDS);
    }

    @Override
    public int playersOnline() {
        Universe u = Universe.get();
        return u == null ? 0 : u.getPlayerCount();
    }

    /** The game's own backup folder: {@code backup/} under the root unless the server's config names another. */
    @Override
    public long[] builtInBackups() {
        Path dir = root.resolve("backup");
        try {
            Path configured = HytaleServer.get().getConfig().getBackupConfig().getDirectory();
            if (configured != null) dir = configured.isAbsolute() ? configured : root.resolve(configured);
        } catch (Throwable ignored) {
            // config not readable yet: the default folder
        }
        if (!Files.isDirectory(dir)) return null;
        long[] sizeCount = new long[2];
        try (Stream<Path> s = Files.walk(dir)) {
            s.filter(Files::isRegularFile).forEach(p -> {
                try {
                    sizeCount[0] += Files.size(p);
                    sizeCount[1]++;
                } catch (IOException ignored) {
                    // gone while counting
                }
            });
        } catch (IOException e) {
            return null;
        }
        return sizeCount;
    }

    @Override
    public boolean builtInBackupsEnabled() {
        try {
            return HytaleServer.get().getConfig().getBackupConfig().isEnabled();
        } catch (Throwable t) {
            return false;
        }
    }

    /** A permission check that also lets the singleplayer owner back up their own world without /op self. */
    public boolean has(CommandSender sender, String permission) {
        return sender.hasPermission(permission) || owner(sender.getUuid());
    }

    private boolean owner(UUID player) {
        try {
            return player != null && singleplayer() && SingleplayerModule.isOwner(player);
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public void reloadSettingsWindow() {
        try {
            var commands = com.hypixel.hytale.server.core.command.system.CommandManager.get();
            if (commands.resolveCommand("modsettings") == null) return;
            commands.handleCommand(com.hypixel.hytale.server.core.console.ConsoleSender.INSTANCE, "modsettings reload");
        } catch (Throwable t) {
            warn("Couldn't ask Mod Settings Manager to reload: " + t.getMessage());
        }
    }

    @Override public void info(String message) { logger.atInfo().log("[WorldBackup] %s", message); }
    @Override public void warn(String message) { logger.atWarning().log("[WorldBackup] %s", message); }

    @Override
    public void tellOps(String message) {
        info("to operators: " + message);
        Universe u = Universe.get();
        if (u == null) return;
        for (PlayerRef p : u.getPlayers()) {
            if (PermissionsModule.get().hasPermission(p.getUuid(), TextCommands.ADMIN) || owner(p.getUuid())) p.sendMessage(Message.raw(message));
        }
    }
}
