package worldbackup;

import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandSender;
import com.hypixel.hytale.server.core.event.events.BootEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerConnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import java.time.Clock;
import java.time.ZoneId;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import worldbackup.app.Backups;
import worldbackup.app.TextCommands;
import worldbackup.hytale.BackupCommand;
import worldbackup.hytale.BackupPage;
import worldbackup.hytale.HytaleGame;
import worldbackup.ui.BackupWindow;

/**
 * WorldBackup's Hytale plugin (design/Hytale.md). Everything that has to happen before a world loads (a
 * staged restore, the pre-update, mod-change and first-run snapshots) runs in {@link #setup()}, which the probe
 * showed comes before the server touches any save. The schedule starts at boot; the shutdown snapshot is taken
 * in {@link #shutdown()}.
 */
public final class WorldBackupPlugin extends JavaPlugin {
    private Backups backups;
    private ScheduledFuture<?> ticker;

    public WorldBackupPlugin(JavaPluginInit init) {
        super(init);
    }

    /** The running service (for test fixtures). */
    public Backups backups() {
        return backups;
    }

    @Override
    protected void setup() {
        HytaleGame game = new HytaleGame(this);
        backups = new Backups(game, Clock.systemDefaultZone());
        game.info("setup(): root " + game.root() + ", data " + game.dataDir() + ", worlds loaded now: "
            + (Universe.get() == null ? "none" : Universe.get().getWorlds().keySet()));
        backups.startup();
        game.info("store " + (backups.store() == null ? "not opened" : backups.store().root()) + "; ready");

        TextCommands text = new TextCommands(backups);
        Function<CommandSender, TextCommands.Actor> actorFor =
            sender -> new TextCommands.Actor(sender.getUsername(), p -> game.has(sender, p));
        getCommandRegistry().registerCommand(new BackupCommand(text, getLogger(), actorFor, ctx -> {
            PlayerRef player = Universe.get().getPlayer(ctx.sender().getUuid());
            TextCommands.Actor actor = actorFor.apply(ctx.sender());
            return player != null && BackupPage.open(player,
                push -> new BackupWindow(backups, actor, push, ZoneId.systemDefault()), getLogger());
        }));

        getEventRegistry().register(BootEvent.class, e -> {
            backups.submit(() -> {
                backups.updateSidecar(); // a brand-new server's worlds exist only now
                return null;
            });
            for (String line : backups.bootNotices()) game.tellOps(line);
            ticker = HytaleServer.SCHEDULED_EXECUTOR.scheduleAtFixedRate(backups::tick, 1, 1, TimeUnit.MINUTES);
        });
        getEventRegistry().registerGlobal(PlayerConnectEvent.class, e -> {
            backups.playersChanged(game.playersOnline());
            String banner = backups.banner();
            if (banner != null && e.getPlayerRef() != null) e.getPlayerRef().sendMessage(Message.raw("[WorldBackup] " + banner));
        });
        // the leaving player may still be counted while the event runs: count again a moment later
        getEventRegistry().registerGlobal(PlayerDisconnectEvent.class, e ->
            HytaleServer.SCHEDULED_EXECUTOR.schedule(() -> backups.playersChanged(game.playersOnline()), 2, TimeUnit.SECONDS));
    }

    @Override
    protected void shutdown() {
        if (ticker != null) ticker.cancel(false);
        if (backups == null) return;
        backups.game().info("shutdown(): worlds still loaded: " + (Universe.get() == null ? "none" : Universe.get().getWorlds().keySet()));
        backups.shutdown();
    }

    /** Mod Settings Manager's live hook (docs/SIDECAR.md): the file is already written when this runs. */
    public boolean onSettingsChanged(Map<String, Object> changed) {
        return backups == null || backups.settingsChanged(changed);
    }
}
