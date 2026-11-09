package core.app.plugin.mcp;

import core.app.GameInput;
import core.app.GameClock;
import core.map.World;
import core.utils.combat.Bullet;
import core.utils.unit.Unit;
import core.utils.unit.enemy.AnglerUnit;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Bridges MCP and terminal commands to the game's simulation thread.
 *
 * <p>Command methods may be called from HTTP workers or input handlers. They
 * enqueue work and return futures; {@link #update(double, boolean)} reads and
 * changes the world, then completes those futures. Callers must not wait for a
 * pending command on the simulation thread itself. A closed controller or full
 * queue produces an exceptionally completed future. Cancelling a queued future
 * skips its command; cancelling an active action stops it when the next update
 * observes the cancellation.
 *
 * <p>Manual play advances while the window is focused. AI control advances both
 * sides only during a requested action, even without focus, and waits between
 * actions. Keyboard pause, restart, and return-to-manual requests remain
 * available in either mode.
 * The game-speed multiplier changes step cadence, while each simulation step
 * retains the same duration and action frame counts.
 */
public final class GameControl implements AutoCloseable {
    /** Fixed simulation frequency used to report elapsed simulation time. */
    public static final int UPDATES_PER_SECOND = GameClock.UPDATES_PER_SECOND;

    /** Normal game speed and the minimum accepted multiplier. */
    public static final double MIN_GAME_SPEED = 1.0;

    /** Fastest accepted multiplier for simulation scheduling. */
    public static final double MAX_GAME_SPEED = 16.0;

    /** Maximum action length: two simulation seconds at the fixed step frequency. */
    public static final int MAX_ACTION_FRAMES = 120;
    private static final int MAX_QUEUED_COMMANDS = 64;

    private final World world;
    private final GameInput input;
    private final ArrayDeque<Request> requests = new ArrayDeque<>();
    private final Map<Unit, Integer> enemyIds = new IdentityHashMap<>();
    private volatile boolean agentControlled;
    private volatile double gameSpeed = MIN_GAME_SPEED;
    private Action action;
    private boolean focused;
    private boolean closed;
    private long tick;
    private int nextEnemyId = 1;
    private int lastActionFrames;
    private double aimX = World.WIDTH * 0.75;
    private double aimY = World.HEIGHT * 0.5;

    /**
     * Creates a controller in manual mode without changing the world.
     *
     * @param world world owned by the simulation thread; must not be {@code null}
     * @param input human input shared with the game window; must not be {@code null}
     */
    public GameControl(World world, GameInput input) {
        this.world = world;
        this.input = input;
    }

    /**
     * Queues a state read without taking control or advancing the simulation.
     *
     * <p>The detached snapshot contains the simulation tick and nominal elapsed
     * seconds, game speed, target update rate, control mode, action progress,
     * focus/pause/game-over flags, arena
     * coordinates, score, wave, player, enemies, and bullets. Unit positions and
     * radii are pixels; angles are radians. Bullet velocities are pixels per
     * second. Enemy IDs remain stable while their units survive and reset with
     * the battle. Snapshot maps and lists are copies, not live world views.
     *
     * @return future completed with a snapshot when the game thread processes
     *         the read, or exceptionally if the controller cannot queue it
     */
    public CompletableFuture<Map<String, Object>> getState() {
        return enqueue(result -> result.complete(snapshot()));
    }

    /**
     * Queues one bounded movement, aiming, and shooting action in AI mode.
     *
     * <p>Starting an action clears held human input and resumes a paused world.
     * Player movement is normalized for diagonal input and constrained to the
     * arena by the unit. Enemies and projectiles advance during the same frames.
     * The result describes the state after the final frame, or an earlier player
     * death or pause. Restart, release, and close interrupt the action with an
     * exceptional result. The battle otherwise waits after the action finishes.
     *
     * <p>Validation runs on the game thread. Invalid arguments complete the
     * future exceptionally with {@link IllegalArgumentException}; an overlapping
     * action or destroyed player uses {@link IllegalStateException}.
     *
     * @param moveX finite horizontal input from {@code -1} (left) to {@code 1} (right)
     * @param moveY finite vertical input from {@code -1} (up) to {@code 1} (down)
     * @param targetX finite aim coordinate from zero through {@link World#WIDTH},
     *                or {@code null} to retain the previous horizontal aim
     * @param targetY finite aim coordinate from zero through {@link World#HEIGHT},
     *                or {@code null} to retain the previous vertical aim
     * @param shoot whether to hold the shooting skill during the action
     * @param frames number of fixed updates, from one through {@link #MAX_ACTION_FRAMES}
     * @return future containing the resulting snapshot, including the number of
     *         frames actually performed in {@code last_action_frames}
     */
    public CompletableFuture<Map<String, Object>> controlPlayer(
            double moveX, double moveY, Double targetX, Double targetY,
            boolean shoot, int frames) {
        return enqueue(result -> {
            if (!Double.isFinite(moveX) || Math.abs(moveX) > 1
                    || !Double.isFinite(moveY) || Math.abs(moveY) > 1) {
                throw new IllegalArgumentException("move_x and move_y must be between -1 and 1.");
            }
            checkAim(targetX, World.WIDTH, "aim_x");
            checkAim(targetY, World.HEIGHT, "aim_y");
            if (frames < 1 || frames > MAX_ACTION_FRAMES) {
                throw new IllegalArgumentException("frames must be an integer between 1 and 120.");
            }
            if (action != null) {
                throw new IllegalStateException("An action is already running; wait for it to finish.");
            }
            if (world.isGameOver()) {
                throw new IllegalStateException("The player is destroyed. Call restart_game first.");
            }
            agentControlled = true;
            input.clear();
            world.setPaused(false);
            if (targetX != null) {
                aimX = targetX;
            }
            if (targetY != null) {
                aimY = targetY;
            }
            lastActionFrames = 0;
            action = new Action(moveX, moveY, shoot, frames, result);
        });
    }

    private static void checkAim(Double value, int maximum, String name) {
        if (value != null && (!Double.isFinite(value) || value < 0 || value > maximum)) {
            throw new IllegalArgumentException(name + " must be between 0 and " + maximum + ".");
        }
    }

    /**
     * Queues a fresh battle and enters AI mode, waiting for the next action.
     *
     * <p>Resets the world, tick, aim, action progress, and enemy ID sequence. Any
     * active action completes exceptionally before the reset snapshot is taken.
     *
     * @return future containing the initial state of the new battle
     */
    public CompletableFuture<Map<String, Object>> restartGame() {
        return enqueue(result -> {
            interruptAction("Action interrupted by restart_game.");
            resetBattle();
            agentControlled = true;
            result.complete(snapshot());
        });
    }

    /**
     * Queues a return to keyboard and mouse control and clears held input.
     *
     * <p>An active AI action completes exceptionally. This does not change the
     * pause flag; manual simulation resumes when focused and unpaused.
     *
     * @return future containing the state after manual mode is selected
     */
    public CompletableFuture<Map<String, Object>> releaseControl() {
        return enqueue(result -> {
            interruptAction("Action interrupted by release_control.");
            input.clear();
            agentControlled = false;
            result.complete(snapshot());
        });
    }

    /**
     * Queues an explicit pause or resume request without changing control mode.
     *
     * <p>Pausing completes an active action normally with its partial snapshot.
     * Resuming AI mode does not itself start an action. The world does not enter
     * the paused state after game over.
     *
     * @param paused {@code true} to pause; {@code false} to resume
     * @return future containing the state after the request is applied
     */
    public CompletableFuture<Map<String, Object>> setPaused(boolean paused) {
        return enqueue(result -> {
            world.setPaused(paused);
            if (world.isPaused() && action != null) {
                CompletableFuture<Map<String, Object>> actionResult = action.result;
                action = null;
                actionResult.complete(snapshot());
            }
            result.complete(snapshot());
        });
    }

    /**
     * Queues a change to simulation speed without taking control or unpausing.
     *
     * <p>The scheduler applies this multiplier to future wall-clock time while
     * gameplay continues to use fixed 1/60-second steps. Action frame counts and
     * simulation-time movement, cooldowns, and projectile lifetimes are unchanged.
     * The selected speed persists across battle restarts.
     *
     * @param multiplier finite speed from {@link #MIN_GAME_SPEED} through
     *                   {@link #MAX_GAME_SPEED}, inclusive
     * @return future containing the state after the speed is applied; invalid
     *         values complete exceptionally with {@link IllegalArgumentException}
     */
    public CompletableFuture<Map<String, Object>> setGameSpeed(double multiplier) {
        return enqueue(result -> {
            if (!Double.isFinite(multiplier) || multiplier < MIN_GAME_SPEED || multiplier > MAX_GAME_SPEED) {
                throw new IllegalArgumentException("Game speed must be a number between 1 and 16.");
            }
            gameSpeed = multiplier;
            result.complete(snapshot());
        });
    }

    /**
     * Returns the speed selected for scheduling future fixed simulation steps.
     *
     * @return the current multiplier, initially 1; safe to read from UI and render threads
     */
    public double getGameSpeed() {
        return gameSpeed;
    }

    private synchronized CompletableFuture<Map<String, Object>> enqueue(
            Consumer<CompletableFuture<Map<String, Object>>> command) {
        CompletableFuture<Map<String, Object>> result = new CompletableFuture<>();
        if (closed) {
            result.completeExceptionally(new IllegalStateException("The game has closed."));
        } else if (requests.size() >= MAX_QUEUED_COMMANDS) {
            result.completeExceptionally(new IllegalStateException("The game command queue is full."));
        } else {
            requests.addLast(new Request(result, command));
        }
        return result;
    }

    /**
     * Processes queued commands and advances at most one simulation frame.
     *
     * <p>The game loop must call this on its simulation thread every fixed tick,
     * including while unfocused, so external commands can complete. Commands
     * queued at the start of this call are processed before simulation advances.
     * Captured human overrides are then applied. Closed controllers ignore
     * updates; waiting AI mode and paused worlds do not advance the battle.
     *
     * @param dt fixed frame duration in seconds, normally
     *           {@code 1.0 / UPDATES_PER_SECOND}
     * @param hasFocus whether the window currently accepts human game input
     */
    public synchronized void update(double dt, boolean hasFocus) {
        if (closed) {
            return;
        }
        focused = hasFocus;
        if (action != null && action.result.isDone()) {
            action = null;
        }
        // Preserve human requests before a queued AI command clears held input.
        boolean releaseRequested = input.consumeReleaseControl();
        boolean restartRequested = input.consumeRestart();
        boolean pauseRequested = input.consumePause();
        Request request;
        // Process only the requests that were already queued at this tick's start.
        int count = requests.size();
        for (int i = 0; i < count && (request = requests.pollFirst()) != null; i++) {
            if (!request.result.isDone()) {
                try {
                    request.command.accept(request.result);
                } catch (RuntimeException exception) {
                    request.result.completeExceptionally(exception);
                }
            }
        }

        if (releaseRequested) {
            interruptAction("Manual control requested with Escape.");
            agentControlled = false;
            input.clear();
        }
        // Human pause and restart work in either control mode.
        if (restartRequested) {
            interruptAction("Action interrupted by keyboard restart.");
            resetBattle();
            return;
        }
        if (pauseRequested) {
            world.setPaused(!world.isPaused());
            if (world.isPaused() && action != null) {
                CompletableFuture<Map<String, Object>> result = action.result;
                action = null;
                result.complete(snapshot());
            }
        }

        if (agentControlled) {
            if (action != null && !world.isPaused()) {
                world.update(dt, action.moveX, action.moveY, aimX, aimY, action.shoot);
                tick++;
                lastActionFrames++;
                action.remainingFrames--;
                if (action.remainingFrames == 0 || world.isGameOver()) {
                    CompletableFuture<Map<String, Object>> result = action.result;
                    action = null;
                    result.complete(snapshot());
                }
            }
        } else if (focused) {
            boolean advancing = !world.isPaused() && !world.isGameOver();
            world.update(dt, input);
            if (advancing) {
                tick++;
            }
        }
    }

    private void resetBattle() {
        world.restart();
        input.clear();
        tick = 0;
        lastActionFrames = 0;
        aimX = World.WIDTH * 0.75;
        aimY = World.HEIGHT * 0.5;
        enemyIds.clear();
        nextEnemyId = 1;
    }

    private void interruptAction(String reason) {
        if (action != null) {
            action.result.completeExceptionally(new IllegalStateException(reason));
            action = null;
        }
    }

    private Map<String, Object> snapshot() {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("tick", tick);
        state.put("simulation_seconds", tick / (double) UPDATES_PER_SECOND);
        state.put("updates_per_second", UPDATES_PER_SECOND);
        state.put("game_speed", gameSpeed);
        state.put("target_updates_per_second", UPDATES_PER_SECOND * gameSpeed);
        state.put("control_mode", agentControlled ? "agent" : "manual");
        state.put("waiting_for_action", agentControlled && action == null);
        state.put("action_frames_remaining", action == null ? 0 : action.remainingFrames);
        state.put("last_action_frames", lastActionFrames);
        state.put("window_focused", focused);
        state.put("paused", world.isPaused());
        state.put("game_over", world.isGameOver());
        state.put("arena", Map.of("width", World.WIDTH, "height", World.HEIGHT,
                "coordinate_origin", "top-left", "positive_x", "right", "positive_y", "down"));
        state.put("score", world.getScore());
        state.put("wave", world.getWave());
        state.put("player", describeUnit(world.getPlayer(), "player"));

        List<Map<String, Object>> enemies = new ArrayList<>();
        enemyIds.keySet().retainAll(world.getEnemies());
        for (AnglerUnit enemy : world.getEnemies()) {
            int id = enemyIds.computeIfAbsent(enemy, ignored -> nextEnemyId++);
            Map<String, Object> description = describeUnit(enemy, "enemy_" + id);
            description.put("attack_range", enemy.getAttackRange());
            enemies.add(description);
        }
        state.put("enemies", enemies);

        List<Map<String, Object>> bullets = new ArrayList<>();
        for (Bullet bullet : world.getBullets()) {
            Map<String, Object> description = new LinkedHashMap<>();
            description.put("x", bullet.getX());
            description.put("y", bullet.getY());
            description.put("velocity_x", bullet.getVelocityX());
            description.put("velocity_y", bullet.getVelocityY());
            description.put("radius", bullet.getRadius());
            description.put("damage", bullet.getDamage());
            description.put("team", bullet.isPlayerOwned() ? "player" : "enemy");
            bullets.add(description);
        }
        state.put("bullets", bullets);
        return state;
    }

    private static Map<String, Object> describeUnit(Unit unit, String id) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
        result.put("x", unit.getX());
        result.put("y", unit.getY());
        result.put("radius", unit.getRadius());
        result.put("angle_radians", unit.getAngle());
        result.put("health", unit.getHealth());
        result.put("max_health", unit.getMaxHealth());
        result.put("alive", unit.isAlive());
        return result;
    }

    /**
     * Reports whether gameplay currently uses bounded AI actions.
     *
     * @return {@code true} in AI mode; {@code false} in manual mode
     */
    public boolean isAgentControlled() {
        return agentControlled;
    }

    /**
     * Reports whether AI mode currently has no active action.
     *
     * @return {@code true} when AI mode is selected and the action slot is empty
     */
    public synchronized boolean isWaitingForAction() {
        return agentControlled && action == null;
    }

    /**
     * Returns the horizontal aim used for the displayed crosshair.
     *
     * @return AI aim in AI mode, otherwise the mouse aim, in pixels from the left
     */
    public synchronized double getAimX() {
        return agentControlled ? aimX : input.getAimX();
    }

    /**
     * Returns the vertical aim used for the displayed crosshair.
     *
     * @return AI aim in AI mode, otherwise the mouse aim, in pixels from the top
     */
    public synchronized double getAimY() {
        return agentControlled ? aimY : input.getAimY();
    }

    /**
     * Stops accepting commands and fails all pending command and action futures.
     *
     * <p>May be called from another thread during window shutdown. Repeated calls
     * have no effect; this controller cannot be reopened. The method does not
     * close the MCP HTTP server or dispose of the game window.
     */
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        interruptAction("The game has closed.");
        Request request;
        while ((request = requests.pollFirst()) != null) {
            request.result.completeExceptionally(new IllegalStateException("The game has closed."));
        }
    }

    private record Request(CompletableFuture<Map<String, Object>> result,
                           Consumer<CompletableFuture<Map<String, Object>>> command) { }

    private static final class Action {
        private final double moveX;
        private final double moveY;
        private final boolean shoot;
        private final CompletableFuture<Map<String, Object>> result;
        private int remainingFrames;

        private Action(double moveX, double moveY, boolean shoot, int frames,
                       CompletableFuture<Map<String, Object>> result) {
            this.moveX = moveX;
            this.moveY = moveY;
            this.shoot = shoot;
            remainingFrames = frames;
            this.result = result;
        }
    }
}
