package core.app.command;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Shares MCP presence, command progress, and recent messages between the game UI
 * and HTTP workers. Instance operations are synchronized, and collection getters
 * return detached snapshots rather than the model's live collections.
 *
 * <p>The message history retains the latest 300 entries. Each message is
 * timestamped in the system time zone, sanitized, and also printed to
 * {@link System#err}. Client names are reported by the client application; they
 * are display information, not authenticated identities. Presence describes
 * recent requests and does not prove that a TCP connection is still open.</p>
 */
public final class GameActivity {
    /** Inactivity duration, in milliseconds, after which a client is displayed as idle. */
    public static final long IDLE_AFTER_MILLIS = 60_000;
    /** Inactivity duration, in milliseconds, after which presence can be expired. */
    public static final long EXPIRE_AFTER_MILLIS = 300_000;
    private static final int MAX_LINES = 300;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    private final LongSupplier clock;
    private final ArrayDeque<String> lines = new ArrayDeque<>();
    private final ArrayDeque<LogEntry> entries = new ArrayDeque<>();
    private final Map<String, ClientView> clients = new LinkedHashMap<>();
    private final Map<Long, String> commands = new LinkedHashMap<>();
    private String serverStatus = "MCP starting";
    private long commandSequence;

    /** Creates an empty activity model using the system clock. */
    public GameActivity() {
        this(System::currentTimeMillis);
    }

    /**
     * Creates an empty activity model with an injectable clock.
     *
     * @param clock supplies epoch milliseconds for messages and presence checks
     * @throws IllegalArgumentException if {@code clock} is {@code null}
     */
    public GameActivity(LongSupplier clock) {
        if (clock == null) throw new IllegalArgumentException("Clock is required");
        this.clock = clock;
    }

    /**
     * Appends a timestamped message to both history views and the run console.
     * Older entries are discarded when the 300-entry limit is exceeded.
     *
     * @param message message to sanitize and truncate at 600 code units, with an
     *                ellipsis if truncated; {@code null} produces an empty message
     */
    public synchronized void log(String message) {
        long timestamp = clock.getAsLong();
        String line = "[" + TIME.format(Instant.ofEpochMilli(timestamp)) + "] "
                + clean(message, 600);
        lines.addLast(line);
        entries.addLast(new LogEntry(timestamp, line));
        while (lines.size() > MAX_LINES) lines.removeFirst();
        while (entries.size() > MAX_LINES) entries.removeFirst();
        System.err.println(line);
    }

    /**
     * Returns the retained, formatted messages in oldest-to-newest order.
     *
     * @return an unmodifiable snapshot of timestamped message lines
     */
    public synchronized List<String> getLines() {
        return List.copyOf(lines);
    }

    /**
     * Returns retained messages with their timestamps for chat fading.
     *
     * @return an unmodifiable snapshot in oldest-to-newest order
     */
    public synchronized List<LogEntry> getEntries() {
        return List.copyOf(entries);
    }

    /**
     * Clears both retained message views. Client presence, running commands, and
     * the command token sequence are preserved; console output is not erased.
     */
    public synchronized void clearLines() {
        lines.clear();
        entries.clear();
    }

    /**
     * Returns the server status text shown by the game HUD.
     *
     * @return the most recently assigned status, initially {@code "MCP starting"}
     */
    public synchronized String getServerStatus() {
        return serverStatus;
    }

    /**
     * Replaces the HUD's server status without adding a history message.
     *
     * @param status status to sanitize and truncate at 180 code units, with an
     *               ellipsis if truncated; {@code null} becomes empty
     */
    public synchronized void setServerStatus(String status) {
        serverStatus = clean(status, 180);
    }

    /**
     * Registers a running command and logs a {@code RUN} notice. The returned
     * token associates its later completion with this exact invocation.
     *
     * @param source human-readable origin, such as a terminal or MCP client name
     * @param command command name and optional argument summary
     * @return the new command token to pass to {@link #commandFinished(long, boolean, String)}
     */
    public synchronized long commandStarted(String source, String command) {
        long token = ++commandSequence;
        String description = clean(source, 100) + ": " + clean(command, 300);
        commands.put(token, description);
        log("RUN #" + token + " " + description);
        return token;
    }

    /**
     * Removes a running command and logs a {@code DONE} or {@code FAIL} notice.
     * Unknown or already completed tokens are ignored.
     *
     * @param token token returned by {@link #commandStarted(String, String)}
     * @param success whether the command completed successfully
     * @param detail optional result or error summary; {@code null} or blank adds no suffix
     */
    public synchronized void commandFinished(long token, boolean success, String detail) {
        String description = commands.remove(token);
        if (description == null) return;
        String suffix = detail == null || detail.isBlank() ? "" : " - " + clean(detail, 180);
        log((success ? "DONE #" : "FAIL #") + token + " " + description + suffix);
    }

    /**
     * Returns how many registered commands have not been completed.
     *
     * @return the number of running command tokens
     */
    public synchronized int getRunningCount() {
        return commands.size();
    }

    /**
     * Returns descriptions of commands that are currently running.
     *
     * @return an unmodifiable snapshot in command-start order
     */
    public synchronized List<String> runningCommandDescriptions() {
        return List.copyOf(commands.values());
    }

    /**
     * Records a client's latest request and refreshes its reported display data.
     * Logs a connection notice for a new logical identity, or an active-again
     * notice when that identity was idle. A blank name is displayed as
     * {@code "Unknown MCP client"}.
     *
     * @param id logical identity key, such as a legacy session ID or modern request identity
     * @param name client-reported application name, not an authenticated identity
     * @param version client-reported application version
     * @param address human-readable remote address
     */
    public synchronized void touchClient(String id, String name, String version, String address) {
        String displayName = clean(name, 80);
        if (displayName.isBlank()) displayName = "Unknown MCP client";
        String displayVersion = clean(version, 40);
        String displayAddress = clean(address, 100);
        ClientView previous = clients.get(id);
        long now = clock.getAsLong();
        clients.put(id, new ClientView(id, displayName, displayVersion, displayAddress, "active", now));
        if (previous == null) {
            log("MCP client connected: " + label(displayName, displayVersion) + " from " + displayAddress
                    + " [" + shortId(id) + "]");
        } else if (now - previous.lastSeenMillis() >= IDLE_AFTER_MILLIS) {
            log("MCP client active again: " + label(displayName, displayVersion)
                    + " [" + shortId(id) + "]");
        }
    }

    /**
     * Returns recent client presence, computing {@code active} or {@code idle}
     * from each last-seen timestamp. This method does not remove expired clients;
     * the server calls {@link #expireInactive()} separately.
     *
     * @return an unmodifiable snapshot in logical-client insertion order
     */
    public synchronized List<ClientView> getClients() {
        long now = clock.getAsLong();
        List<ClientView> result = new ArrayList<>();
        for (ClientView client : clients.values()) {
            String status = now - client.lastSeenMillis() >= IDLE_AFTER_MILLIS ? "idle" : "active";
            result.add(new ClientView(client.id(), client.name(), client.version(), client.address(),
                    status, client.lastSeenMillis()));
        }
        return List.copyOf(result);
    }

    /**
     * Removes a known logical client and logs its disconnection reason.
     * Unknown identities are ignored.
     *
     * @param id identity key previously supplied to {@link #touchClient(String, String, String, String)}
     * @param reason human-readable reason, such as an ended legacy session
     */
    public synchronized void disconnectClient(String id, String reason) {
        ClientView removed = clients.remove(id);
        if (removed != null) {
            log("MCP client disconnected: " + label(removed.name(), removed.version())
                    + " [" + shortId(id) + "] - " + clean(reason, 160));
        }
    }

    /**
     * Removes clients with no requests for at least {@link #EXPIRE_AFTER_MILLIS}
     * and logs an inactivity notice for each. Expiry is not proof that a TCP
     * connection disconnected.
     *
     * @return detached list of removed identity keys, for transport-session cleanup
     */
    public synchronized List<String> expireInactive() {
        long now = clock.getAsLong();
        List<String> expired = new ArrayList<>();
        clients.values().removeIf(client -> {
            if (now - client.lastSeenMillis() < EXPIRE_AFTER_MILLIS) return false;
            expired.add(client.id());
            log("MCP client inactive: " + label(client.name(), client.version())
                    + " [" + shortId(client.id()) + "] - no requests for 5 minutes");
            return true;
        });
        return expired;
    }

    /**
     * Removes all recent clients, logging {@code server stopped} as their reason.
     * Message history and running-command records remain available.
     */
    public synchronized void closeClients() {
        for (String id : new ArrayList<>(clients.keySet())) {
            disconnectClient(id, "server stopped");
        }
    }

    /**
     * Produces bounded, single-line display text by replacing control, format,
     * and Unicode line-separator characters with spaces, then trimming it.
     * If the source exceeds the limit, an ellipsis is appended after truncation.
     *
     * @param text source text; {@code null} becomes an empty string
     * @param limit maximum number of UTF-16 code units copied before an optional ellipsis
     * @return sanitized display text including any truncation ellipsis
     */
    public static String clean(String text, int limit) {
        if (text == null) return "";
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < text.length() && result.length() < limit; i++) {
            char character = text.charAt(i);
            if (Character.isISOControl(character) || Character.getType(character) == Character.FORMAT
                    || character == '\u2028' || character == '\u2029') {
                result.append(' ');
            } else {
                result.append(character);
            }
        }
        if (text.length() > limit) result.append("...");
        return result.toString().trim();
    }

    private static String label(String name, String version) {
        return name + (version.isBlank() ? "" : " " + version);
    }

    private static String shortId(String id) {
        return clean(id, 24);
    }

    /**
     * Immutable presence snapshot for one logical MCP client.
     *
     * @param id logical identity key used to correlate requests
     * @param name reported application name, or the unknown-client fallback
     * @param version reported application version
     * @param address human-readable remote address
     * @param status activity status, normally {@code active} or {@code idle}
     * @param lastSeenMillis epoch milliseconds of the latest observed request
     */
    public record ClientView(String id, String name, String version, String address,
                             String status, long lastSeenMillis) { }

    /**
     * Immutable timestamped message retained for the terminal renderer.
     *
     * @param timestampMillis epoch milliseconds when the message was logged
     * @param text sanitized message including its formatted local-time prefix
     */
    public record LogEntry(long timestampMillis, String text) { }
}
