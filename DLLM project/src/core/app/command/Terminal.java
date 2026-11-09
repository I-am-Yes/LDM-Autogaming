package core.app.command;

import core.app.plugin.mcp.GameControl;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Displays Minecraft-style chat, local slash commands, and shared MCP activity
 * inside the battlefield.
 *
 * <p>AWT key events edit the input while the render thread draws the overlay.
 * Shared editing and drawing state is synchronized; gameplay commands are
 * queued through {@link GameControl} and complete asynchronously on the game
 * thread. The host must suppress normal game input while {@link #isOpen()}
 * returns {@code true}; this class does not change the game's input listeners.
 *
 * <p>Pressing T opens ordinary chat. Pressing / opens a command line containing
 * the leading slash. Enter logs ordinary text locally or submits a slash
 * command, and Escape closes the input. Both local commands and MCP requests
 * share the activity history supplied by {@link GameActivity}.
 */
public final class Terminal implements AutoCloseable {
    private static final Font FONT = new Font(Font.MONOSPACED, Font.PLAIN, 13);
    private static final int MAX_INPUT = 256;
    private static final long MESSAGE_LIFETIME = 10_000;
    private final GameActivity activity;
    private final GameControl control;
    private final Runnable returnToGame;
    private final StringBuilder input = new StringBuilder();
    private final List<String> history = new ArrayList<>();
    private final List<ChatLine> wrappedLines = new ArrayList<>();
    private List<GameActivity.LogEntry> cachedEntries = List.of();
    private int cachedWidth;
    private int caret;
    private int historyIndex;
    private int scrollOffset;
    private String historyDraft = "";
    private Character suppressTyped;
    private boolean open;

    /**
     * Creates an overlay and logs the chat-opening instructions.
     *
     * @param activity shared chat, command, and MCP activity history
     * @param control game-thread command queue used for gameplay operations
     * @param returnToGame callback invoked when an open input line is closed,
     *                     typically to restore gameplay input and keyboard focus
     * @throws NullPointerException if any argument is {@code null}
     */
    public Terminal(GameActivity activity, GameControl control, Runnable returnToGame) {
        this.activity = Objects.requireNonNull(activity);
        this.control = Objects.requireNonNull(control);
        this.returnToGame = Objects.requireNonNull(returnToGame);
        activity.log("Press T for chat, / for commands. Type /help for commands.");
    }

    /**
     * Opens an empty input line and resets its caret, draft, and scroll position.
     * Command history and activity messages are retained.
     */
    public synchronized void open() {
        open = true;
        input.setLength(0);
        caret = 0;
        historyIndex = history.size();
        historyDraft = "";
        scrollOffset = 0;
        suppressTyped = null;
    }

    /**
     * Reports whether the overlay is accepting chat or command input.
     *
     * @return {@code true} while the input line is open
     */
    public synchronized boolean isOpen() {
        return open;
    }

    /**
     * Handles chat opening, editing, history navigation, submission, and closing.
     *
     * <p>T and an unshifted / open the closed input. While open, all key presses
     * are handled here, including Enter to submit, Escape to close, arrow keys
     * to edit or recall history, and Page Up/Down to scroll messages. The return
     * value tells the host whether to consume the AWT event; this method does
     * not call {@link KeyEvent#consume()} itself.
     *
     * @param event non-null AWT key-pressed event
     * @return {@code true} if chat handled the key, including an opening key;
     *         {@code false} if the closed overlay did not handle it
     */
    public boolean handleKeyPressed(KeyEvent event) {
        String entered = null;
        boolean closeRequested = false;
        synchronized (this) {
            if (!open) {
                if (event.isControlDown() || event.isAltDown() || event.isMetaDown()) return false;
                if (event.getKeyCode() == KeyEvent.VK_T) {
                    open();
                    suppressTyped = 't';
                    return true;
                }
                if (event.getKeyCode() == KeyEvent.VK_SLASH && !event.isShiftDown()) {
                    open();
                    input.append('/');
                    caret = 1;
                    suppressTyped = '/';
                    return true;
                }
                return false;
            }
            switch (event.getKeyCode()) {
                case KeyEvent.VK_ENTER -> {
                    entered = input.toString().trim();
                    closeRequested = true;
                }
                case KeyEvent.VK_ESCAPE -> closeRequested = true;
                case KeyEvent.VK_BACK_SPACE -> {
                    if (caret > 0) {
                        int previous = input.offsetByCodePoints(caret, -1);
                        input.delete(previous, caret);
                        caret = previous;
                    }
                }
                case KeyEvent.VK_DELETE -> {
                    if (caret < input.length()) input.delete(caret, input.offsetByCodePoints(caret, 1));
                }
                case KeyEvent.VK_LEFT -> {
                    if (caret > 0) caret = input.offsetByCodePoints(caret, -1);
                }
                case KeyEvent.VK_RIGHT -> {
                    if (caret < input.length()) caret = input.offsetByCodePoints(caret, 1);
                }
                case KeyEvent.VK_HOME -> caret = 0;
                case KeyEvent.VK_END -> caret = input.length();
                case KeyEvent.VK_UP -> browseHistory(-1);
                case KeyEvent.VK_DOWN -> browseHistory(1);
                case KeyEvent.VK_PAGE_UP -> scrollOffset += 5;
                case KeyEvent.VK_PAGE_DOWN -> scrollOffset = Math.max(0, scrollOffset - 5);
                default -> { }
            }
        }
        if (closeRequested) {
            close();
            if (entered != null && !entered.isEmpty()) {
                if (entered.startsWith("/")) {
                    submitCommand(entered);
                } else {
                    remember(entered);
                    activity.log("You: " + entered);
                }
            }
        }
        return true;
    }

    /**
     * Inserts a typed character at the caret while the input line is open.
     *
     * <p>The character generated by an opening T or / press is suppressed once,
     * preventing an unwanted T or duplicate slash. Control characters, modified
     * shortcuts, and characters beyond the 256-character input limit are not
     * inserted, but their events are still handled while chat is open.
     *
     * @param event non-null AWT key-typed event
     * @return {@code true} if the input line is open and owns the event,
     *         otherwise {@code false}
     */
    public synchronized boolean handleKeyTyped(KeyEvent event) {
        if (!open) return false;
        char character = event.getKeyChar();
        if (suppressTyped != null) {
            char suppressed = suppressTyped;
            suppressTyped = null;
            if (Character.toLowerCase(character) == suppressed) return true;
        }
        if (!event.isControlDown() && !event.isAltDown() && !event.isMetaDown()
                && !Character.isISOControl(character) && character != KeyEvent.CHAR_UNDEFINED
                && input.length() < MAX_INPUT) {
            input.insert(caret++, character);
            scrollOffset = 0;
        }
        return true;
    }

    /**
     * Parses and submits a local terminal command without blocking for gameplay.
     *
     * <p>The leading slash is optional for this direct API. Help, history
     * clearing, and client listing finish immediately; state reads, pause,
     * resume, restart, release, movement, aiming, firing, and stepping use
     * {@link GameControl}. Movement accepts {@code move dx dy [frames]}, aiming
     * accepts {@code aim x y}, and {@code fire [frames]} or {@code step [frames]}
     * accept 1 to 120 frames, defaulting to 30. {@code next step} is an alias
     * for {@code step 1}. Actions enter stepped AI control; {@code release}
     * restores manual play. {@code speed} reports the current multiplier, while
     * {@code speed multiplier} sets a finite multiplier from 1 to 16. Speed
     * changes wall-clock update cadence, not the number or duration of simulated
     * frames in an action.
     *
     * <p>Accepted nonempty commands are added to command history and logged as
     * running, then completed or failed. Validation and execution failures
     * complete the returned future exceptionally rather than being thrown
     * directly. A null or blank command completes successfully without action.
     *
     * @param text command text, optionally prefixed with /; may be {@code null}
     * @return a future completed when the local operation finishes, or completed
     *         exceptionally for invalid syntax, arguments, or gameplay failure
     */
    public CompletableFuture<Void> submitCommand(String text) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        String line = text == null ? "" : text.trim();
        if (line.startsWith("/")) line = line.substring(1).trim();
        if (line.isEmpty()) {
            result.complete(null);
            return result;
        }
        if (line.length() > MAX_INPUT) {
            activity.log("Command rejected: maximum length is " + MAX_INPUT + " characters.");
            result.completeExceptionally(new IllegalArgumentException("Command is too long."));
            return result;
        }
        remember("/" + line);
        long token = activity.commandStarted("Terminal", "/" + line);
        try {
            String[] args = line.split("\\s+");
            String name = args[0].toLowerCase(Locale.ROOT);
            CompletableFuture<Map<String, Object>> operation;
            switch (name) {
                case "help" -> {
                    count(args, 1, 1, "/help");
                    activity.log("/help | /clear | /clients | /state | /pause | /resume | /restart | /release");
                    activity.log("/move <dx> <dy> [frames] | /aim <x> <y> | /fire [frames] | /step [frames]");
                    activity.log("/next step = /step 1 | /speed [multiplier] (1-16, default 1x)");
                    activity.log("Actions use AI control and wait between steps. Frames: 1-120, default 30. /release restores manual play.");
                    finish(token, "Help displayed.", result, null);
                    return result;
                }
                case "clear" -> {
                    count(args, 1, 1, "/clear");
                    activity.clearLines();
                    finish(token, "Chat history cleared.", result, null);
                    return result;
                }
                case "clients" -> {
                    count(args, 1, 1, "/clients");
                    List<GameActivity.ClientView> clients = activity.getClients();
                    if (clients.isEmpty()) activity.log("MCP clients: none seen recently.");
                    for (GameActivity.ClientView client : clients) {
                        activity.log("MCP client " + client.name() + " " + client.version() + " | "
                                + client.address() + " | " + client.status() + " | " + client.id());
                    }
                    finish(token, clients.size() + " recent MCP client(s).", result, null);
                    return result;
                }
                case "state" -> {
                    count(args, 1, 1, "/state");
                    operation = control.getState();
                }
                case "speed" -> {
                    count(args, 1, 2, "/speed [multiplier]");
                    operation = args.length == 1 ? control.getState()
                            : control.setGameSpeed(number(args[1]));
                }
                case "pause", "resume" -> {
                    count(args, 1, 1, "/" + name);
                    operation = control.setPaused(name.equals("pause"));
                }
                case "restart" -> {
                    count(args, 1, 1, "/restart");
                    operation = control.restartGame();
                }
                case "release" -> {
                    count(args, 1, 1, "/release");
                    operation = control.releaseControl();
                }
                case "move" -> {
                    count(args, 3, 4, "/move <dx> <dy> [frames]");
                    operation = control.controlPlayer(number(args[1]), number(args[2]),
                            null, null, false, frames(args, 3));
                }
                case "aim" -> {
                    count(args, 3, 3, "/aim <x> <y>");
                    operation = control.controlPlayer(0, 0, number(args[1]), number(args[2]), false, 1);
                }
                case "fire", "step" -> {
                    count(args, 1, 2, "/" + name + " [frames]");
                    operation = control.controlPlayer(0, 0, null, null, name.equals("fire"), frames(args, 1));
                }
                case "next" -> {
                    count(args, 2, 2, "/next step");
                    if (!args[1].equalsIgnoreCase("step")) {
                        throw new IllegalArgumentException("Usage: /next step");
                    }
                    operation = control.controlPlayer(0, 0, null, null, false, 1);
                }
                default -> throw new IllegalArgumentException("Unknown command '/" + args[0] + "'. Type /help.");
            }
            operation.whenComplete((state, error) -> {
                Throwable failure = unwrap(error);
                finish(token, failure == null ? summarize(state) : failure.getMessage(), result, failure);
            });
        } catch (RuntimeException exception) {
            finish(token, exception.getMessage(), result, exception);
        }
        return result;
    }

    private void finish(long token, String detail, CompletableFuture<Void> result, Throwable error) {
        activity.commandFinished(token, error == null, detail == null ? "Command failed." : detail);
        if (error == null) result.complete(null);
        else result.completeExceptionally(error);
    }

    private static Throwable unwrap(Throwable error) {
        while (error instanceof CompletionException && error.getCause() != null) error = error.getCause();
        return error;
    }

    private static void count(String[] args, int minimum, int maximum, String usage) {
        if (args.length < minimum || args.length > maximum) throw new IllegalArgumentException("Usage: " + usage);
    }

    private static double number(String text) {
        try {
            double value = Double.parseDouble(text);
            if (Double.isFinite(value)) return value;
        } catch (NumberFormatException ignored) { }
        throw new IllegalArgumentException("Expected a finite number, got '" + text + "'.");
    }

    private static int frames(String[] args, int index) {
        if (args.length <= index) return 30;
        try {
            int value = Integer.parseInt(args[index]);
            if (value >= 1 && value <= GameControl.MAX_ACTION_FRAMES) return value;
        } catch (NumberFormatException ignored) { }
        throw new IllegalArgumentException("frames must be an integer from 1 to 120.");
    }

    private static String summarize(Map<String, Object> state) {
        if (!(state.get("player") instanceof Map<?, ?> player)) return "Command completed.";
        String position = String.format(Locale.ROOT, "(%.1f, %.1f)",
                ((Number) player.get("x")).doubleValue(), ((Number) player.get("y")).doubleValue());
        return "tick " + state.get("tick") + " | " + state.get("control_mode")
                + " | speed " + state.get("game_speed") + "x"
                + " | player " + position + " HP " + player.get("health") + "/" + player.get("max_health")
                + " | wave " + state.get("wave") + " score " + state.get("score")
                + (Boolean.TRUE.equals(state.get("paused")) ? " | paused" : "")
                + (Boolean.TRUE.equals(state.get("game_over")) ? " | game over" : "");
    }

    private synchronized void remember(String line) {
        if (history.isEmpty() || !history.get(history.size() - 1).equals(line)) {
            history.add(line);
            if (history.size() > 100) history.remove(0);
        }
        historyIndex = history.size();
        historyDraft = "";
    }

    private void browseHistory(int direction) {
        if (history.isEmpty()) return;
        if (historyIndex == history.size()) historyDraft = input.toString();
        historyIndex = Math.max(0, Math.min(history.size(), historyIndex + direction));
        input.setLength(0);
        input.append(historyIndex == history.size() ? historyDraft : history.get(historyIndex));
        caret = input.length();
    }

    /**
     * Draws the chat overlay over an already rendered world and HUD.
     *
     * <p>An open input shows up to ten wrapped message rows and the command
     * field, with history scrolling. A closed input shows up to five recent
     * rows, fading them during the final two seconds of their ten-second
     * lifetime. Drawing uses and disposes a child graphics context, preserving
     * the caller's graphics state and ownership.
     *
     * @param graphics non-null graphics context for the game viewport
     * @param width viewport width in pixels
     * @param height viewport height in pixels
     */
    public synchronized void draw(Graphics2D graphics, int width, int height) {
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setFont(FONT);
            FontMetrics metrics = g.getFontMetrics();
            int chatWidth = Math.max(80, Math.min(width - 24, 780));
            List<GameActivity.LogEntry> entries = activity.getEntries();
            if (chatWidth != cachedWidth || !entries.equals(cachedEntries)) {
                wrapEntries(entries, metrics, chatWidth - 12);
                cachedWidth = chatWidth;
                cachedEntries = entries;
            }
            int rowHeight = metrics.getHeight() + 2;
            int maxRows = open ? 10 : 5;
            int bottom = open ? height - 44 : height - 43;
            scrollOffset = Math.min(scrollOffset, Math.max(0, wrappedLines.size() - maxRows));
            int end = wrappedLines.size() - (open ? scrollOffset : 0);
            int start = Math.max(0, end - maxRows);
            long now = System.currentTimeMillis();
            int drawn = 0;
            for (int i = end - 1; i >= start; i--) {
                ChatLine line = wrappedLines.get(i);
                long age = Math.max(0, now - line.timestamp);
                if (!open && age >= MESSAGE_LIFETIME) continue;
                double opacity = open ? 1 : Math.min(1, (MESSAGE_LIFETIME - age) / 2_000.0);
                int y = bottom - (++drawn) * rowHeight;
                g.setColor(new Color(0, 0, 0, (int) (160 * opacity)));
                g.fillRect(8, y - 1, chatWidth, rowHeight);
                g.setColor(new Color(235, 240, 243, (int) (255 * opacity)));
                g.drawString(line.text, 13, y + metrics.getAscent());
            }
            if (open) drawInput(g, metrics, width, height);
        } finally {
            g.dispose();
        }
    }

    private void wrapEntries(List<GameActivity.LogEntry> entries, FontMetrics metrics, int width) {
        wrappedLines.clear();
        for (GameActivity.LogEntry entry : entries) {
            String remaining = entry.text();
            while (!remaining.isEmpty()) {
                int end = Math.min(remaining.length(), Math.max(1, width / metrics.charWidth('M')));
                while (end > 1 && metrics.stringWidth(remaining.substring(0, end)) > width) end--;
                while (end < remaining.length() && metrics.stringWidth(remaining.substring(0, end + 1)) <= width) end++;
                wrappedLines.add(new ChatLine(remaining.substring(0, end), entry.timestampMillis()));
                remaining = remaining.substring(end);
            }
        }
    }

    private void drawInput(Graphics2D g, FontMetrics metrics, int width, int height) {
        int y = height - 29;
        g.setColor(new Color(0, 0, 0, 245));
        g.fillRect(4, y, width - 8, 25);
        g.setColor(new Color(175, 190, 200));
        g.drawRect(4, y, width - 9, 24);
        Graphics2D field = (Graphics2D) g.create();
        try {
            field.clipRect(10, y + 1, width - 20, 23);
            String text = input.toString();
            int shift = Math.max(0, metrics.stringWidth(text.substring(0, caret)) - (width - 34));
            int baseline = y + 4 + metrics.getAscent();
            field.setColor(Color.WHITE);
            field.drawString(text, 11 - shift, baseline);
            if ((System.currentTimeMillis() / 500) % 2 == 0) {
                int x = 11 + metrics.stringWidth(text.substring(0, caret)) - shift;
                field.fillRect(x, y + 4, 1, metrics.getHeight());
            }
        } finally {
            field.dispose();
        }
        g.setColor(new Color(225, 230, 235));
        g.drawString("Chat / commands  -  Enter: send   Esc: close   Up/Down: history   PgUp/PgDn: scroll",
                12, height - 34);
    }

    /**
     * Closes the input line, discards its draft, and restores gameplay focus.
     *
     * <p>The callback supplied to the constructor runs only if the input was
     * open. Message and command history remain available; this operation does
     * not stop the MCP server or cancel commands already submitted to the game.
     */
    @Override
    public void close() {
        boolean wasOpen;
        synchronized (this) {
            wasOpen = open;
            open = false;
            input.setLength(0);
            caret = 0;
            suppressTyped = null;
        }
        if (wasOpen) returnToGame.run();
    }

    private record ChatLine(String text, long timestamp) { }
}
