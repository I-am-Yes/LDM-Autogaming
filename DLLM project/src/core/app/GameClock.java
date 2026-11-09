package core.app;

/**
 * Converts wall-clock time into fixed simulation steps at the selected speed.
 * Speed changes how often steps run; each step still represents 1/60 simulation
 * second. Long stalls are bounded so rendering and input remain responsive.
 */
public final class GameClock {
    /** Number of fixed steps in one simulation second. */
    public static final int UPDATES_PER_SECOND = 60;
    /** Duration passed to gameplay for every simulation step. */
    public static final double STEP_SECONDS = 1.0 / UPDATES_PER_SECOND;
    /** Maximum number of catch-up steps returned by one scheduling cycle. */
    public static final int MAX_UPDATES_PER_CYCLE = 60;
    private static final double MAX_ELAPSED_SECONDS = 0.25;

    private double accumulatedSeconds;

    /** Creates a clock with no accumulated simulation time. */
    public GameClock() { }

    /**
     * Accumulates scaled time and returns the number of fixed steps due now.
     *
     * <p>A cycle accounts for at most 0.25 wall-clock seconds and returns at most
     * {@link #MAX_UPDATES_PER_CYCLE} steps. Excess whole-step backlog is dropped;
     * the fractional remainder is retained for the next cycle.
     *
     * @param elapsedSeconds finite, nonnegative wall-clock time since the last cycle
     * @param multiplier finite, positive speed multiplier for this cycle
     * @return number of fixed simulation steps to execute
     * @throws IllegalArgumentException if elapsed time or multiplier is invalid
     */
    public int advance(double elapsedSeconds, double multiplier) {
        if (!Double.isFinite(elapsedSeconds) || elapsedSeconds < 0) {
            throw new IllegalArgumentException("Elapsed time must be finite and nonnegative.");
        }
        if (!Double.isFinite(multiplier) || multiplier <= 0) {
            throw new IllegalArgumentException("Game speed must be finite and positive.");
        }
        accumulatedSeconds += Math.min(elapsedSeconds, MAX_ELAPSED_SECONDS) * multiplier;
        double due = Math.floor(accumulatedSeconds / STEP_SECONDS + 1e-9);
        accumulatedSeconds = Math.max(0, accumulatedSeconds - due * STEP_SECONDS);
        return (int) Math.min(due, MAX_UPDATES_PER_CYCLE);
    }
}
