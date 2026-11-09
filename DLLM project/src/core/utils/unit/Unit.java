package core.utils.unit;

import core.utils.combat.Bullet;
import core.utils.skill.ShootingSkill;

/** Position, health, movement and a shooting skill shared by every unit. */
public abstract class Unit {
    private double x;
    private double y;
    private double angle;
    private final double radius;
    private final int maxHealth;
    private int health;
    private final double speed;
    private final boolean playerControlled;
    protected final ShootingSkill shooting;

    protected Unit(double x, double y, double radius, int maxHealth, double speed,
                   boolean playerControlled, double fireInterval, double bulletSpeed, int bulletDamage) {
        this.x = x;
        this.y = y;
        this.radius = radius;
        this.maxHealth = maxHealth;
        health = maxHealth;
        this.speed = speed;
        this.playerControlled = playerControlled;
        shooting = new ShootingSkill(fireInterval, bulletSpeed, bulletDamage);
    }

    public void update(double dt) {
        shooting.update(dt);
    }

    public void move(double dx, double dy, double dt, int width, int height) {
        if (!isAlive()) {
            return;
        }
        double length = Math.hypot(dx, dy);
        if (length > 1) {
            dx /= length;
            dy /= length;
        }
        x = clamp(x + dx * speed * Math.max(0, dt), radius, width - radius);
        y = clamp(y + dy * speed * Math.max(0, dt), radius, height - radius);
    }

    private static double clamp(double value, double minimum, double maximum) {
        return maximum < minimum ? (minimum + maximum) / 2 : Math.max(minimum, Math.min(maximum, value));
    }

    public void aimAt(double targetX, double targetY) {
        if (Math.hypot(targetX - x, targetY - y) > 0.0001) {
            angle = Math.atan2(targetY - y, targetX - x);
        }
    }

    public Bullet fireAt(double targetX, double targetY) {
        return shooting.fire(this, targetX, targetY);
    }

    public void damage(int amount) {
        health = Math.max(0, health - Math.max(0, amount));
    }

    public double getX() { return x; }
    public double getY() { return y; }
    public double getRadius() { return radius; }
    public double getAngle() { return angle; }
    public int getHealth() { return health; }
    public int getMaxHealth() { return maxHealth; }
    public boolean isAlive() { return health > 0; }
    public boolean isPlayerControlled() { return playerControlled; }
}
