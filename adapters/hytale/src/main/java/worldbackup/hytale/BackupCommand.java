package worldbackup.hytale;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.CommandSender;
import com.hypixel.hytale.server.core.command.system.ParseResult;
import com.hypixel.hytale.server.core.command.system.ParserContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractAsyncCommand;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import worldbackup.app.TextCommands;

/**
 * {@code /worldbackup}, alias {@code /wbak}. Anyone may run it; subcommands that change things check
 * {@code worldbackup.create} or {@code worldbackup.admin} themselves (operators and the singleplayer owner have
 * both). With no arguments
 * a player gets the window; the console gets the usage.
 */
public final class BackupCommand extends AbstractAsyncCommand {
    private final TextCommands text;
    private final HytaleLogger logger;
    /** Who the sender is, with live permission checks. */
    private final Function<CommandSender, TextCommands.Actor> actorFor;
    /** Opens the window for a player; returns false if the player isn't in a world. */
    private final Function<CommandContext, Boolean> openWindow;

    /** The line as typed (as in PlaytestGamified: the context only has it rebuilt from tokens). */
    private static final ThreadLocal<String> RAW_INPUT = new ThreadLocal<>();

    public BackupCommand(TextCommands text, HytaleLogger logger, Function<CommandSender, TextCommands.Actor> actorFor,
                         Function<CommandContext, Boolean> openWindow) {
        super(TextCommands.NAME, "Backs up your worlds; restores, pins and lists snapshots");
        this.text = text;
        this.logger = logger;
        this.actorFor = actorFor;
        this.openWindow = openWindow;
        addAliases(TextCommands.ALIAS);
        setAllowsExtraArguments(true);
        requireNoPermission();
    }

    @Override
    public CompletableFuture<Void> acceptCall(CommandSender sender, ParserContext parser, ParseResult result) {
        RAW_INPUT.set(parser.getRawInput());
        try {
            return super.acceptCall(sender, parser, result);
        } finally {
            RAW_INPUT.remove();
        }
    }

    @Override
    protected CompletableFuture<Void> executeAsync(CommandContext context) {
        String raw = RAW_INPUT.get();
        List<String> args = TextCommands.split(raw != null ? raw : context.getInputString());
        CommandSender sender = context.sender();
        TextCommands.Actor actor = actorFor.apply(sender);
        CompletableFuture<List<String>> lines;
        try {
            lines = text.run(actor, args);
        } catch (RuntimeException e) {
            logger.atSevere().withCause(e).log("[WorldBackup] /%s failed", TextCommands.NAME);
            context.sendMessage(Message.raw("/" + TextCommands.NAME + " failed: " + e.getMessage()));
            return CompletableFuture.completedFuture(null);
        }
        if (lines == null) {
            if (!context.isPlayer() || !openWindow.apply(context)) TextCommands.usage().forEach(l -> context.sendMessage(Message.raw(l)));
            return CompletableFuture.completedFuture(null);
        }
        return lines.handle((ls, err) -> {
            if (err != null) {
                logger.atSevere().withCause(err).log("[WorldBackup] /%s failed", TextCommands.NAME);
                context.sendMessage(Message.raw("/" + TextCommands.NAME + " failed: " + err.getMessage()));
            } else {
                ls.forEach(l -> context.sendMessage(Message.raw(l)));
            }
            return null;
        });
    }
}
