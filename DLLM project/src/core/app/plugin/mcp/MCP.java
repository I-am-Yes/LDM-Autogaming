package core.app.plugin.mcp;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import core.app.command.GameActivity;
import core.map.World;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Exposes the battle game to local MCP clients through Streamable HTTP.
 *
 * <p>The server binds to {@code 127.0.0.1} and accepts JSON-RPC requests at
 * {@code /mcp}. It supports protocol versions {@code 2026-07-28},
 * {@code 2025-11-25}, {@code 2025-06-18}, and {@code 2025-03-26}, using JSON
 * responses rather than a standalone server-sent event stream.
 *
 * <p>The advertised tools delegate to the simulation's command queue:
 * <ul>
 *   <li>{@code get_game_state}: reads a detached snapshot without taking control.</li>
 *   <li>{@code control_player}: moves, aims, and optionally fires for 1–120
 *       updates. Movement components range from -1 to 1; aim coordinates are
 *       pixels from the top-left. Omitted movement and shooting values default
 *       to zero and false, omitted aim coordinates retain the last aim, and
 *       omitted frame count defaults to 30.</li>
 *   <li>{@code restart_game}: resets the battle and waits in AI control.</li>
 *   <li>{@code release_control}: restores keyboard and mouse control.</li>
 *   <li>{@code set_game_speed}: sets the required finite {@code multiplier}
 *       from 1 to 16, with 1 as the game's initial speed.</li>
 * </ul>
 * <p>Each simulation update advances 1/60 second. Game speed multiplies the
 * wall-clock update cadence without changing action frame counts or the fixed
 * simulation step. Active AI actions also advance while the window is unfocused.
 * Between AI actions the battle waits regardless of speed. The caller must
 * continue pumping {@link GameControl#update(double, boolean)}; HTTP workers
 * never modify the world directly. Tool calls wait up to five seconds, then
 * cancel their queued or active game request and return a tool error.
 *
 * <p>Legacy initialization returns an optional session ID for associating later
 * requests with the reported client name and version. Modern requests supply
 * their own identity metadata each time. Presence, command starts, results, and
 * failures are recorded in {@link GameActivity} for the HUD, chat, and run
 * console. Reported client names are display information, not authentication.
 *
 * <p>Create an instance, call {@link #start()}, then call {@link #close()} when
 * the game window closes. Closing this server does not close the game or its
 * {@code GameControl} instance.
 *
 * @see GameControl
 * @see GameActivity
 */
public final class MCP implements AutoCloseable {
    /** Default listening port used by the game window. */
    public static final int DEFAULT_PORT = 8765;
    private static final String MODERN = "2026-07-28";
    private static final String LEGACY = "2025-11-25";
    private static final List<String> VERSIONS = List.of(MODERN, LEGACY, "2025-06-18", "2025-03-26");
    private static final Map<String, Object> SERVER_INFO = Map.of("name", "decision-models-battle", "version", "1.0.0");
    private static final Map<String, Object> CAPABILITIES = Map.of("tools", Map.of());
    private static final String INSTRUCTIONS = "Control the player unit in a 2D battle. Coordinates are pixels, "
            + "origin at top left, x right and y down. Read get_game_state first. control_player enters AI mode "
            + "and advances both sides for the requested frames. Each frame is 1/60 simulation second. "
            + "set_game_speed changes the wall-clock update cadence from 1x to 16x without changing frame counts. "
            + "Between AI actions the battle waits, including while the window is unfocused. "
            + "release_control resumes human play.";
    private static final int MAX_BODY = 65_536;

    private final GameControl control;
    private final int requestedPort;
    private final GameActivity activity;
    private final Map<String, ClientIdentity> sessions = new ConcurrentHashMap<>();
    private HttpServer server;
    private ThreadPoolExecutor executor;
    private ScheduledExecutorService maintenance;
    private boolean closed;

    /**
     * Creates an unstarted server with its own activity history.
     *
     * @param control bridge serviced by the running simulation thread
     * @param port loopback listening port, or zero to choose an available port
     * @throws IllegalArgumentException if {@code control} is null or the port is
     *         outside 0–65535
     */
    public MCP(GameControl control, int port) {
        this(control, port, new GameActivity());
    }

    /**
     * Creates an unstarted server that shares activity with the game UI.
     *
     * @param control bridge serviced by the running simulation thread
     * @param port loopback listening port, or zero to choose an available port
     * @param activity shared client-presence and command history model
     * @throws IllegalArgumentException if either object is null or the port is
     *         outside 0–65535
     */
    public MCP(GameControl control, int port, GameActivity activity) {
        if (control == null) throw new IllegalArgumentException("GameControl is required");
        if (activity == null) throw new IllegalArgumentException("GameActivity is required");
        if (port < 0 || port > 65535) throw new IllegalArgumentException("Port must be between 0 and 65535");
        this.control = control;
        this.activity = activity;
        requestedPort = port;
    }

    /**
     * Starts the loopback HTTP endpoint, request workers, and presence cleanup.
     *
     * <p>Calling this again while running has no effect. The server records its
     * endpoint in the activity history, or records a startup failure if binding
     * fails. This method does not start the game loop.
     *
     * @throws IOException if the HTTP server cannot bind to the requested address
     * @throws IllegalStateException if this instance has already been closed
     */
    public synchronized void start() throws IOException {
        if (closed) throw new IllegalStateException("MCP server has been closed");
        if (server != null) return;
        HttpServer created;
        try {
            created = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), requestedPort), 16);
        } catch (IOException exception) {
            activity.setServerStatus("MCP unavailable");
            activity.log("MCP startup failed: " + exception.getMessage());
            throw exception;
        }
        executor = new ThreadPoolExecutor(2, 4, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(16), task -> {
                    Thread thread = new Thread(task, "battle-mcp-http");
                    thread.setDaemon(true);
                    return thread;
                });
        created.setExecutor(executor);
        created.createContext("/mcp", this::handle);
        server = created;
        created.start();
        activity.setServerStatus("MCP online: " + getEndpoint());
        activity.log("MCP server ready: " + getEndpoint());
        maintenance = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "battle-mcp-presence");
            thread.setDaemon(true);
            return thread;
        });
        maintenance.scheduleAtFixedRate(() -> {
            for (String id : activity.expireInactive()) {
                sessions.values().removeIf(client -> client.id.equals(id));
            }
        }, 5, 5, TimeUnit.SECONDS);
    }

    /**
     * Returns the live, thread-safe activity model used by this server.
     *
     * @return the supplied activity instance, or the one created by the
     *         two-argument constructor
     */
    public GameActivity getActivity() {
        return activity;
    }

    /**
     * Returns the local MCP endpoint URL.
     *
     * @return {@code http://127.0.0.1:<port>/mcp}, using the bound port while
     *         running and the requested port otherwise; a requested port of zero
     *         becomes usable only after {@link #start()}
     */
    public synchronized String getEndpoint() {
        int port = server == null ? requestedPort : server.getAddress().getPort();
        return "http://127.0.0.1:" + port + "/mcp";
    }

    /**
     * Stops HTTP handling and presence cleanup, clears clients, and logs shutdown.
     *
     * <p>Repeated calls have no effect. Interrupted HTTP workers cancel the game
     * requests they are waiting for. This method does not close {@link GameControl}
     * or dispose the game window; the owner remains responsible for those.
     */
    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        if (server != null) {
            server.stop(0);
            server = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        if (maintenance != null) {
            maintenance.shutdownNow();
            maintenance = null;
        }
        sessions.clear();
        activity.closeClients();
        activity.setServerStatus("MCP offline");
        activity.log("MCP server stopped");
    }

    /**
     * Validates one HTTP/JSON-RPC exchange, associates its reported client,
     * dispatches the method, and records command completion or failure.
     *
     * @param exchange request whose body and response are closed on return
     * @throws IOException if reading the request or writing the response fails
     */
    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!"/mcp".equals(exchange.getRequestURI().getPath())) {
                sendError(exchange, 404, null, -32601, "Unknown endpoint", null);
                return;
            }
            if (!validLocalHost(exchange)) {
                sendError(exchange, 403, null, -32000, "Only localhost access is allowed", null);
                return;
            }
            String origin = exchange.getRequestHeaders().getFirst("Origin");
            if (origin != null) {
                if (!validOrigin(origin)) {
                    sendError(exchange, 403, null, -32000, "Origin is not a loopback address", null);
                    return;
                }
                exchange.getResponseHeaders().set("Access-Control-Allow-Origin", origin);
                exchange.getResponseHeaders().set("Vary", "Origin");
                exchange.getResponseHeaders().set("Access-Control-Expose-Headers", "MCP-Protocol-Version, Mcp-Session-Id");
            }
            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "POST, GET, DELETE, OPTIONS");
                exchange.getResponseHeaders().set("Access-Control-Allow-Headers",
                        "Content-Type, Accept, MCP-Protocol-Version, Mcp-Method, Mcp-Name, Mcp-Session-Id");
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            if ("DELETE".equals(exchange.getRequestMethod()) && deleteSession(exchange)) {
                return;
            }
            if (!"POST".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Allow", "POST, OPTIONS");
                sendError(exchange, 405, null, -32000, "This JSON-only MCP endpoint accepts POST requests", null);
                return;
            }
            String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
            if (contentType == null || !contentType.split(";", 2)[0].trim().equalsIgnoreCase("application/json")) {
                sendError(exchange, 415, null, -32600, "Content-Type must be application/json", null);
                return;
            }
            String accept = exchange.getRequestHeaders().getFirst("Accept");
            if (!accepts(accept, "application/json") || !accepts(accept, "text/event-stream")) {
                sendError(exchange, 406, null, -32600,
                        "Accept must include application/json and text/event-stream", null);
                return;
            }
            byte[] bytes = exchange.getRequestBody().readNBytes(MAX_BODY + 1);
            if (bytes.length > MAX_BODY) {
                sendError(exchange, 413, null, -32600, "MCP request exceeds 64 KiB", null);
                return;
            }
            Object parsed;
            try {
                String text = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes)).toString();
                parsed = Json.parse(text);
            } catch (IllegalArgumentException | CharacterCodingException exception) {
                sendError(exchange, 400, null, -32700, "Invalid JSON: " + exception.getMessage(), null);
                return;
            }
            if (!(parsed instanceof Map<?, ?>)) {
                sendError(exchange, 400, null, -32600, "Expected one JSON-RPC request object", null);
                return;
            }
            Map<String, Object> request = object(parsed);
            Object id = request.get("id");
            boolean hasId = request.containsKey("id");
            if (!"2.0".equals(request.get("jsonrpc")) || !(request.get("method") instanceof String)
                    || (hasId && !validId(id)) || (request.containsKey("params") && !(request.get("params") instanceof Map<?, ?>))) {
                sendError(exchange, 400, null, -32600, "Invalid JSON-RPC request", null);
                return;
            }
            String method = (String) request.get("method");
            Map<String, Object> params = request.containsKey("params") ? object(request.get("params")) : Map.of();
            boolean modern;
            try {
                modern = validateVersion(exchange, method, params);
            } catch (RpcException exception) {
                sendError(exchange, exception.status, id, exception.code, exception.getMessage(), exception.data);
                return;
            }
            if (!hasId) {
                // This server does not request sampling, progress, cancellation or other client work.
                try {
                    ClientIdentity client = identifyClient(exchange, method, params, modern);
                    observe(client);
                } catch (RpcException exception) {
                    sendError(exchange, exception.status, null, exception.code, exception.getMessage(), exception.data);
                    return;
                }
                exchange.sendResponseHeaders(202, -1);
                return;
            }
            long command = 0;
            try {
                ClientIdentity client = null;
                if (!"initialize".equals(method) || modern) {
                    client = identifyClient(exchange, method, params, modern);
                    observe(client);
                    command = activity.commandStarted(client.source(), describeCommand(method, params));
                }
                Map<String, Object> result = dispatch(method, params, modern);
                if ("initialize".equals(method) && !modern) {
                    String sessionId = UUID.randomUUID().toString();
                    client = namedClient("session:" + sessionId, object(params.get("clientInfo")), address(exchange));
                    sessions.put(sessionId, client);
                    exchange.getResponseHeaders().set("Mcp-Session-Id", sessionId);
                    observe(client);
                    command = activity.commandStarted(client.source(), "initialize");
                }
                boolean success = !Boolean.TRUE.equals(result.get("isError"));
                activity.commandFinished(command, success, success ? "completed" : toolFailure(result));
                command = 0;
                if (modern) {
                    result = new LinkedHashMap<>(result);
                    result.put("resultType", "complete");
                    result.put("_meta", Map.of("io.modelcontextprotocol/serverInfo", SERVER_INFO));
                }
                send(exchange, 200, map("jsonrpc", "2.0", "id", id, "result", result));
            } catch (RpcException exception) {
                if (command == 0) activity.log("MCP " + method + " rejected: " + exception.getMessage());
                else activity.commandFinished(command, false, exception.getMessage());
                sendError(exchange, exception.status, id, exception.code, exception.getMessage(), exception.data);
            } catch (RuntimeException exception) {
                activity.commandFinished(command, false, exception.getMessage());
                sendError(exchange, 500, id, -32603, "Internal MCP server error", null);
            }
        }
    }

    /**
     * Handles an explicit legacy-session disconnect, leaving modern HTTP stateless.
     *
     * @param exchange DELETE request after endpoint and origin validation
     * @return true if a legacy session header was handled, including an unknown ID;
     *         false if the caller should return the normal unsupported-method response
     * @throws IOException if the disconnect response cannot be written
     */
    private boolean deleteSession(HttpExchange exchange) throws IOException {
        if (MODERN.equals(exchange.getRequestHeaders().getFirst("MCP-Protocol-Version"))) return false;
        String sessionId = exchange.getRequestHeaders().getFirst("Mcp-Session-Id");
        if (sessionId == null) return false;
        ClientIdentity client = sessions.remove(sessionId);
        if (client == null) {
            sendError(exchange, 404, null, -32600, "Unknown MCP session; initialize again", null);
        } else {
            activity.disconnectClient(client.id, "client ended its MCP session");
            exchange.sendResponseHeaders(204, -1);
        }
        return true;
    }

    /**
     * Resolves per-request modern identity or an explicit legacy session ID.
     * Missing identity is displayed as unknown rather than inherited from another
     * request or client using the same loopback address.
     *
     * @param exchange request containing transport headers and remote address
     * @param method validated JSON-RPC method name
     * @param params validated request parameters
     * @param modern whether per-request modern metadata is in use
     * @return reported client identity used only for presence and logging
     * @throws RpcException if an explicit legacy session ID is not recognized
     */
    private ClientIdentity identifyClient(HttpExchange exchange, String method,
                                          Map<String, Object> params, boolean modern) throws RpcException {
        String remoteAddress = address(exchange);
        if (modern) {
            Map<String, Object> meta = object(params.get("_meta"));
            Object info = meta.get("io.modelcontextprotocol/clientInfo");
            if (info instanceof Map<?, ?> details) {
                ClientIdentity named = namedClient("", object(details), remoteAddress);
                return new ClientIdentity("modern:" + named.name + "|" + named.version + "|" + remoteAddress,
                        named.name, named.version, remoteAddress);
            }
        } else if (!"initialize".equals(method)) {
            String sessionId = exchange.getRequestHeaders().getFirst("Mcp-Session-Id");
            if (sessionId != null) {
                ClientIdentity client = sessions.get(sessionId);
                if (client == null) throw new RpcException(404, -32600, "Unknown MCP session; initialize again");
                return client;
            }
        }
        String agent = GameActivity.clean(exchange.getRequestHeaders().getFirst("User-Agent"), 80);
        String name = "Unknown MCP client" + (agent.isBlank() ? "" : " (" + agent + ")");
        return new ClientIdentity("unidentified:" + remoteAddress + "|" + agent, name, "", remoteAddress);
    }

    private static ClientIdentity namedClient(String id, Map<String, Object> info, String address) {
        String name = info.get("name") instanceof String value ? GameActivity.clean(value, 80) : "";
        String version = info.get("version") instanceof String value ? GameActivity.clean(value, 40) : "";
        if (name.isBlank()) name = "Unknown MCP client";
        return new ClientIdentity(id, name, version, address);
    }

    private static String address(HttpExchange exchange) {
        return exchange.getRemoteAddress().getAddress().getHostAddress();
    }

    private synchronized void observe(ClientIdentity client) {
        if (!closed) activity.touchClient(client.id, client.name, client.version, client.address);
    }

    private static String describeCommand(String method, Map<String, Object> params) {
        if (!"tools/call".equals(method)) return method;
        String arguments = params.get("arguments") instanceof Map<?, ?> values
                ? " " + GameActivity.clean(Json.stringify(values), 240) : "";
        return String.valueOf(params.getOrDefault("name", "tools/call")) + arguments;
    }

    private static String toolFailure(Map<String, Object> result) {
        if (result.get("content") instanceof List<?> content && !content.isEmpty()
                && content.get(0) instanceof Map<?, ?> text && text.get("text") instanceof String message) {
            return message;
        }
        return "command failed";
    }

    private record ClientIdentity(String id, String name, String version, String address) {
        private String source() {
            return "MCP " + name + (version.isBlank() ? "" : " " + version);
        }
    }

    /**
     * Checks protocol support and modern header/body consistency before dispatch.
     *
     * @param exchange request containing mirrored protocol, method, and tool headers
     * @param method JSON-RPC method from the request body
     * @param params request parameters containing modern metadata when applicable
     * @return true for modern per-request metadata, false for legacy negotiation
     * @throws RpcException if required metadata is absent, headers disagree, or
     *         the requested protocol version is unsupported
     */
    private boolean validateVersion(HttpExchange exchange, String method, Map<String, Object> params) throws RpcException {
        String header = exchange.getRequestHeaders().getFirst("MCP-Protocol-Version");
        Map<String, Object> meta = params.get("_meta") instanceof Map<?, ?> ? object(params.get("_meta")) : Map.of();
        Object metaVersion = meta.get("io.modelcontextprotocol/protocolVersion");
        boolean modern = MODERN.equals(header) || metaVersion != null;
        if (modern) {
            if (!(metaVersion instanceof String)) {
                throw new RpcException(400, -32602, "Modern requests require a protocol version in params._meta");
            }
            if (header != null && !header.equals(metaVersion)) {
                throw new RpcException(400, -32020, "MCP protocol header does not match the request body");
            }
            Object requested = metaVersion == null ? header : metaVersion;
            if (!MODERN.equals(requested)) {
                throw unsupportedVersion(requested);
            }
            if (!(meta.get("io.modelcontextprotocol/clientCapabilities") instanceof Map<?, ?>)) {
                throw new RpcException(400, -32602, "Modern requests require protocol version and client capabilities in params._meta");
            }
            if (!MODERN.equals(header) || !method.equals(exchange.getRequestHeaders().getFirst("Mcp-Method"))) {
                throw new RpcException(400, -32020, "MCP protocol/method headers do not match the request body");
            }
            if ("tools/call".equals(method)) {
                String name = exchange.getRequestHeaders().getFirst("Mcp-Name");
                if (name != null && name.startsWith("=?base64?") && name.endsWith("?=")) {
                    try {
                        name = new String(Base64.getDecoder().decode(name.substring(9, name.length() - 2)), StandardCharsets.UTF_8);
                    } catch (IllegalArgumentException exception) {
                        throw new RpcException(400, -32020, "Invalid Mcp-Name header encoding");
                    }
                }
                if (!(params.get("name") instanceof String) || !params.get("name").equals(name)) {
                    throw new RpcException(400, -32020, "Mcp-Name header does not match the tool name");
                }
            }
        } else if (header != null && !VERSIONS.contains(header)) {
            throw unsupportedVersion(header);
        }
        return modern;
    }

    private RpcException unsupportedVersion(Object requested) {
        return new RpcException(400, -32022, "Unsupported MCP protocol version",
                map("supported", VERSIONS, "requested", requested));
    }

    private Map<String, Object> dispatch(String method, Map<String, Object> params, boolean modern) throws RpcException {
        return switch (method) {
            case "initialize" -> {
                if (modern) throw new RpcException(404, -32601, "Modern MCP uses server/discover instead of initialize");
                if (!(params.get("protocolVersion") instanceof String requested)
                        || !(params.get("capabilities") instanceof Map<?, ?>)
                        || !(params.get("clientInfo") instanceof Map<?, ?>)) {
                    throw new RpcException(400, -32602, "initialize requires protocolVersion, capabilities and clientInfo");
                }
                String version = VERSIONS.contains(requested) && !MODERN.equals(requested) ? requested : LEGACY;
                yield map("protocolVersion", version, "capabilities", CAPABILITIES,
                        "serverInfo", SERVER_INFO, "instructions", INSTRUCTIONS);
            }
            case "server/discover" -> {
                if (!modern) throw new RpcException(200, -32601, "Method not found: " + method);
                yield map("supportedVersions", VERSIONS, "capabilities", CAPABILITIES, "instructions", INSTRUCTIONS);
            }
            case "ping" -> Map.of();
            case "tools/list" -> Map.of("tools", tools());
            case "tools/call" -> callTool(params);
            default -> throw new RpcException(modern ? 404 : 200, -32601, "Method not found: " + method);
        };
    }

    /**
     * Validates a tool invocation and waits for its game-thread result.
     *
     * <p>Unknown tools and malformed envelopes produce JSON-RPC errors. Argument
     * validation, gameplay failures, interruptions, and timeouts become tool
     * results with {@code isError=true}. Successful results include the snapshot
     * in both text content and {@code structuredContent}.
     *
     * @param params {@code tools/call} parameters with a name and optional arguments
     * @return MCP tool result, including a failure message when execution fails
     * @throws RpcException if the tool name or arguments envelope is invalid
     */
    private Map<String, Object> callTool(Map<String, Object> params) throws RpcException {
        if (!(params.get("name") instanceof String name)
                || (params.containsKey("arguments") && !(params.get("arguments") instanceof Map<?, ?>))) {
            throw new RpcException(400, -32602, "tools/call requires a tool name and object arguments");
        }
        if (!Set.of("get_game_state", "control_player", "restart_game", "release_control", "set_game_speed").contains(name)) {
            throw new RpcException(400, -32602, "Unknown tool: " + name);
        }
        Map<String, Object> args = params.containsKey("arguments") ? object(params.get("arguments")) : Map.of();
        CompletableFuture<Map<String, Object>> future = null;
        try {
            if ("control_player".equals(name)) {
                checkKeys(args, Set.of("move_x", "move_y", "aim_x", "aim_y", "shoot", "frames"));
                double moveX = number(args, "move_x", 0, -1, 1);
                double moveY = number(args, "move_y", 0, -1, 1);
                Double aimX = args.containsKey("aim_x") ? number(args, "aim_x", 0, 0, World.WIDTH) : null;
                Double aimY = args.containsKey("aim_y") ? number(args, "aim_y", 0, 0, World.HEIGHT) : null;
                Object shooting = args.getOrDefault("shoot", Boolean.FALSE);
                if (!(shooting instanceof Boolean)) throw new IllegalArgumentException("shoot must be a boolean");
                double frames = number(args, "frames", 30, 1, 120);
                if (frames != Math.rint(frames)) throw new IllegalArgumentException("frames must be an integer");
                future = control.controlPlayer(moveX, moveY, aimX, aimY, (Boolean) shooting, (int) frames);
            } else if ("set_game_speed".equals(name)) {
                checkKeys(args, Set.of("multiplier"));
                if (!args.containsKey("multiplier")) {
                    throw new IllegalArgumentException("multiplier is required");
                }
                future = control.setGameSpeed(number(args, "multiplier", GameControl.MIN_GAME_SPEED,
                        GameControl.MIN_GAME_SPEED, GameControl.MAX_GAME_SPEED));
            } else {
                checkKeys(args, Set.of());
                future = switch (name) {
                    case "get_game_state" -> control.getState();
                    case "restart_game" -> control.restartGame();
                    default -> control.releaseControl();
                };
            }
            Map<String, Object> state = future.get(5, TimeUnit.SECONDS);
            return map("content", List.of(map("type", "text", "text", Json.stringify(state))),
                    "structuredContent", state, "isError", false);
        } catch (TimeoutException exception) {
            if (future != null) future.cancel(false);
            return toolError("Game thread did not respond within 5 seconds. The action was cancelled.");
        } catch (InterruptedException exception) {
            if (future != null) future.cancel(false);
            Thread.currentThread().interrupt();
            return toolError("MCP request was interrupted and cancelled.");
        } catch (ExecutionException exception) {
            return toolError(message(exception.getCause()));
        } catch (IllegalArgumentException | IllegalStateException exception) {
            return toolError(message(exception));
        }
    }

    private static String message(Throwable exception) {
        return exception == null || exception.getMessage() == null ? "Game command failed" : exception.getMessage();
    }

    private static Map<String, Object> toolError(String message) {
        return map("content", List.of(map("type", "text", "text", message)), "isError", true);
    }

    private static void checkKeys(Map<String, Object> args, Set<String> allowed) {
        for (String key : args.keySet()) {
            if (!allowed.contains(key)) throw new IllegalArgumentException("Unknown argument: " + key);
        }
    }

    private static double number(Map<String, Object> args, String key, double defaultValue, double min, double max) {
        Object value = args.getOrDefault(key, defaultValue);
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())
                || number.doubleValue() < min || number.doubleValue() > max) {
            throw new IllegalArgumentException(key + " must be a number between " + min + " and " + max);
        }
        return number.doubleValue();
    }

    private static List<Map<String, Object>> tools() {
        Map<String, Object> emptySchema = map("type", "object", "properties", Map.of(), "additionalProperties", false);
        Map<String, Object> properties = map(
                "move_x", map("type", "number", "minimum", -1, "maximum", 1, "default", 0,
                        "description", "Horizontal movement: -1 left, 0 still, 1 right."),
                "move_y", map("type", "number", "minimum", -1, "maximum", 1, "default", 0,
                        "description", "Vertical movement: -1 up, 0 still, 1 down."),
                "aim_x", map("type", "number", "minimum", 0, "maximum", World.WIDTH,
                        "description", "Aim at this pixel x; omitted keeps the last aim."),
                "aim_y", map("type", "number", "minimum", 0, "maximum", World.HEIGHT,
                        "description", "Aim at this pixel y; omitted keeps the last aim."),
                "shoot", map("type", "boolean", "default", false,
                        "description", "Hold the shooting skill during these frames."),
                "frames", map("type", "integer", "minimum", 1, "maximum", 120, "default", 30,
                        "description", "Advance both sides for this many 1/60 second updates, then return the state."));
        List<Map<String, Object>> tools = new ArrayList<>();
        tools.add(tool("get_game_state", "Read player/enemy/bullet positions, health, wave, score, game speed and control mode. "
                + "Does not take control or advance time. Coordinates use pixels, origin top left.", emptySchema, true));
        tools.add(tool("control_player", "Take AI control, move/aim/fire for 1..120 frames, and return the resulting state. "
                + "The whole battle waits between AI actions, including while the window is unfocused. "
                + "Both player and enemies update during the action. Speed changes wall-clock cadence, not frame counts. "
                + "Use release_control to resume human play.",
                map("type", "object", "properties", properties, "additionalProperties", false), false));
        tools.add(tool("restart_game", "Restart the battle and enter AI control. The battle waits for control_player actions.",
                emptySchema, false));
        tools.add(tool("release_control", "Return control to the human keyboard/mouse. Real-time play resumes when the game is focused.",
                emptySchema, false));
        tools.add(map("name", "set_game_speed", "description",
                "Set game speed from 1x to 16x (initially 1x). Changes wall-clock simulation update cadence; "
                        + "each frame still advances 1/60 simulation second and action frame counts stay exact. "
                        + "Does not take player control or advance a waiting AI battle.",
                "inputSchema", map("type", "object", "properties", map("multiplier",
                        map("type", "number", "minimum", GameControl.MIN_GAME_SPEED,
                                "maximum", GameControl.MAX_GAME_SPEED,
                                "description", "Finite speed multiplier, such as 1, 1.5, 2, 4, 8 or 16.")),
                        "required", List.of("multiplier"), "additionalProperties", false),
                "annotations", map("readOnlyHint", false, "destructiveHint", false,
                        "idempotentHint", true, "openWorldHint", false)));
        return tools;
    }

    private static Map<String, Object> tool(String name, String description, Map<String, Object> schema, boolean readOnly) {
        return map("name", name, "description", description, "inputSchema", schema,
                "annotations", map("readOnlyHint", readOnly, "destructiveHint", !readOnly,
                        "idempotentHint", readOnly, "openWorldHint", false));
    }

    private boolean validLocalHost(HttpExchange exchange) {
        if (!exchange.getRemoteAddress().getAddress().isLoopbackAddress()) return false;
        String host = exchange.getRequestHeaders().getFirst("Host");
        if (host == null) return false;
        try {
            URI uri = URI.create("http://" + host);
            return localName(uri.getHost()) && (uri.getPort() == -1 || uri.getPort() == exchange.getLocalAddress().getPort())
                    && uri.getUserInfo() == null && uri.getRawQuery() == null && uri.getRawFragment() == null
                    && (uri.getRawPath() == null || uri.getRawPath().isEmpty());
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static boolean validOrigin(String origin) {
        try {
            URI uri = URI.create(origin);
            return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    && localName(uri.getHost()) && uri.getUserInfo() == null
                    && uri.getRawQuery() == null && uri.getRawFragment() == null
                    && (uri.getRawPath() == null || uri.getRawPath().isEmpty());
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static boolean localName(String host) {
        return "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "[::1]".equals(host) || "::1".equals(host);
    }

    private static boolean accepts(String header, String mediaType) {
        if (header == null) return false;
        for (String value : header.toLowerCase(Locale.ROOT).split(",")) {
            String[] parts = value.trim().split(";");
            if (parts[0].trim().equals(mediaType)) {
                boolean allowed = true;
                for (int i = 1; i < parts.length; i++) {
                    if (parts[i].trim().matches("q=0(?:\\.0*)?")) allowed = false;
                }
                if (allowed) return true;
            }
        }
        return false;
    }

    private static boolean validId(Object id) {
        return id instanceof String || id instanceof Number number
                && (!(number instanceof BigDecimal decimal) || decimal.stripTrailingZeros().scale() <= 0);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        return (Map<String, Object>) value;
    }

    private static Map<String, Object> map(Object... entries) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < entries.length; i += 2) map.put((String) entries[i], entries[i + 1]);
        return map;
    }

    private static void sendError(HttpExchange exchange, int status, Object id, int code, String message, Object data) throws IOException {
        Map<String, Object> error = map("code", code, "message", message);
        if (data != null) error.put("data", data);
        Map<String, Object> response = map("jsonrpc", "2.0", "error", error);
        boolean modern = MODERN.equals(exchange.getRequestHeaders().getFirst("MCP-Protocol-Version"))
                || exchange.getRequestHeaders().getFirst("Mcp-Method") != null;
        if (id != null || !modern) response.put("id", id);
        send(exchange, status, response);
    }

    private static void send(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] response = Json.stringify(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, response.length);
        exchange.getResponseBody().write(response);
    }

    private static final class RpcException extends Exception {
        private static final long serialVersionUID = 1L;
        private final int status;
        private final int code;
        private final Object data;

        private RpcException(int status, int code, String message) {
            this(status, code, message, null);
        }

        private RpcException(int status, int code, String message, Object data) {
            super(message);
            this.status = status;
            this.code = code;
            this.data = data;
        }
    }
}
