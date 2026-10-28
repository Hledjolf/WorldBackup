package worldbackup.ui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * UI commands for a raw {@code .ui} page, built without Hytale types. The adapter turns each into the
 * matching {@code UICommandBuilder} or {@code UIEventBuilder} call, in order. Copied from PlaytestGamified
 * (the family copies patterns rather than depending on each other).
 */
public final class UiOps {
    public sealed interface Op permits Set, Entries, Clear, Append, Bind {}

    /** Sets a property; the value is a String, Boolean or Double. */
    public record Set(String selector, Object value) implements Op {}

    /** Sets a dropdown's entries. */
    public record Entries(String selector, List<Entry> entries) implements Op {}

    public record Entry(String label, String value) {}

    public record Clear(String selector) implements Op {}

    /** Appends a document (path relative to {@code Common/UI/Custom}) inside a container. */
    public record Append(String selector, String document) implements Op {}

    /**
     * Binds an event. {@code event} is a Hytale binding type name ({@code Activating}, {@code ValueChanged});
     * data entries whose key starts with {@code @} ask the client to send that element property's value.
     */
    public record Bind(String selector, String event, Map<String, String> data) implements Op {}

    private final List<Op> ops = new ArrayList<>();
    private String chatCommand;

    public UiOps set(String selector, Object value) {
        if (!(value instanceof String || value instanceof Boolean || value instanceof Double)) {
            throw new IllegalArgumentException("UI values are String, Boolean or Double, not " + (value == null ? "null" : value.getClass().getSimpleName()));
        }
        ops.add(new Set(selector, value));
        return this;
    }

    public UiOps text(String selector, String text) {
        return set(selector + ".Text", text == null ? "" : text);
    }

    public UiOps visible(String selector, boolean visible) {
        return set(selector + ".Visible", visible);
    }

    public UiOps entries(String selector, List<Entry> entries) {
        ops.add(new Entries(selector, List.copyOf(entries)));
        return this;
    }

    public UiOps clear(String selector) {
        ops.add(new Clear(selector));
        return this;
    }

    public UiOps append(String selector, String document) {
        ops.add(new Append(selector, document));
        return this;
    }

    public UiOps bind(String selector, String event, Map<String, String> data) {
        ops.add(new Bind(selector, event, new LinkedHashMap<>(data)));
        return this;
    }

    public UiOps addAll(UiOps other) {
        ops.addAll(other.ops);
        if (other.chatCommand != null) chatCommand = other.chatCommand;
        return this;
    }

    /**
     * After these ops: close the window and open the player's chat with {@code command} typed in, not sent. The
     * adapter does it after sending the ops.
     */
    public UiOps openChat(String command) {
        chatCommand = command;
        return this;
    }

    /** The command to open chat with, or null. */
    public String chatCommand() {
        return chatCommand;
    }

    public List<Op> ops() {
        return List.copyOf(ops);
    }

    public boolean isEmpty() {
        return ops.isEmpty() && chatCommand == null;
    }

    /** The last value set for a selector, or null (for tests). */
    public Object valueOf(String selector) {
        Object v = null;
        for (Op op : ops) if (op instanceof Set s && s.selector().equals(selector)) v = s.value();
        return v;
    }

    /** All binds, for tests. */
    public List<Bind> binds() {
        return ops.stream().filter(o -> o instanceof Bind).map(o -> (Bind) o).toList();
    }

    /** All appends into a container, for tests. */
    public List<String> appendsInto(String selector) {
        return ops.stream().filter(o -> o instanceof Append a && a.selector().equals(selector)).map(o -> ((Append) o).document()).toList();
    }

    @Override
    public String toString() {
        StringBuilder b = new StringBuilder();
        for (Op op : ops) b.append(op).append('\n');
        return b.toString();
    }
}
