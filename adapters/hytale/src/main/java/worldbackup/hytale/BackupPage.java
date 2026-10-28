package worldbackup.hytale;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import worldbackup.core.Json;
import worldbackup.ui.BackupWindow;
import worldbackup.ui.UiOps;

/**
 * The window as a raw {@code .ui} page (as PlaytestGamified's journal). All state and layout live in
 * {@link BackupWindow}; this class builds the page, decodes the client's events and sends the window's
 * updates, including the ones that arrive later from the backup worker.
 */
public final class BackupPage extends InteractiveCustomUIPage<BackupPage.NoEvent> {
    /** Events are decoded from the raw JSON, so the typed codec is never used. */
    public static final class NoEvent {
        static final BuilderCodec<NoEvent> CODEC = BuilderCodec.builder(NoEvent.class, NoEvent::new).build();
    }

    private final BackupWindow window;
    private final HytaleLogger logger;
    private volatile boolean closed;

    private BackupPage(PlayerRef player, Function<Consumer<UiOps>, BackupWindow> makeWindow, HytaleLogger logger) {
        super(player, CustomPageLifetime.CanDismiss, NoEvent.CODEC);
        this.logger = logger;
        this.window = makeWindow.apply(this::send);
    }

    /** Opens the page for a player, on the player's world thread. Returns false if the player isn't in a world. */
    public static boolean open(PlayerRef player, Function<Consumer<UiOps>, BackupWindow> makeWindow, HytaleLogger logger) {
        Ref<EntityStore> ref = player.getReference();
        if (ref == null || !ref.isValid()) return false;
        World world = ref.getStore().getExternalData().getWorld();
        Runnable r = () -> {
            Ref<EntityStore> now = player.getReference();
            if (now == null || !now.isValid()) return;
            Store<EntityStore> store = now.getStore();
            Player p = store.getComponent(now, Player.getComponentType());
            if (p != null) p.getPageManager().openCustomPage(now, store, new BackupPage(player, makeWindow, logger));
        };
        if (world.isInThread()) r.run();
        else world.execute(r);
        return true;
    }

    @Override
    public void build(Ref<EntityStore> ref, UICommandBuilder cmd, UIEventBuilder events, Store<EntityStore> store) {
        cmd.append(BackupWindow.DOC);
        UiTranslate.into(window.open(), cmd, events);
    }

    @Override
    public void handleDataEvent(Ref<EntityStore> ref, Store<EntityStore> store, String rawData) {
        Map<String, Object> data;
        try {
            data = new LinkedHashMap<>(Json.parseObject(rawData));
        } catch (RuntimeException bad) {
            logger.atWarning().log("[WorldBackup] undecodable page event %s", rawData);
            return;
        }
        Object action = data.remove("Action");
        try {
            send(window.onEvent(action == null ? null : action.toString(), data));
        } catch (RuntimeException e) {
            logger.atSevere().withCause(e).log("[WorldBackup] window failed on %s", action);
        }
    }

    @Override
    public void onDismiss(Ref<EntityStore> ref, Store<EntityStore> store) {
        closed = true;
        window.close();
        super.onDismiss(ref, store);
    }

    private void send(UiOps ops) {
        // A late update must never land on another page the player opened since.
        if (closed || ops == null || ops.ops().isEmpty()) return;
        UICommandBuilder cmd = new UICommandBuilder();
        UIEventBuilder ev = new UIEventBuilder();
        UiTranslate.into(ops, cmd, ev);
        sendUpdate(cmd, ev, false);
    }
}
