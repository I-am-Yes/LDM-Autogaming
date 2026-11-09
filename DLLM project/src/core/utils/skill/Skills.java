package core.utils.skill;

/** Shared cooldown behavior for shooting and future unit skills. */
public abstract class Skills {
    private final double cooldown;
    private double remaining;

    protected Skills(double cooldown) {
        this.cooldown = cooldown;
    }

    public final void update(double dt) {
        remaining = Math.max(0, remaining - Math.max(0, dt));
    }

    public final boolean isReady() {
        return remaining <= 0;
    }

    public final void delay(double seconds) {
        remaining = Math.max(0, seconds);
    }

    protected final void startCooldown() {
        remaining = cooldown;
    }
}
