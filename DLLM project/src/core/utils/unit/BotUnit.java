package core.utils.unit;

import core.utils.combat.Bullet;

/** Simple ranged AI: approach a target, keep distance and strafe while firing. */
public class BotUnit extends Unit {
    private final double attackRange;
    private final double preferredDistance;
    private final double strafeDirection;

    public BotUnit(double x, double y) {
        this(x, y, 18, 50, 85, 1.2, 270, 10, 700, 300);
    }

    protected BotUnit(double x, double y, double radius, int maxHealth, double speed,
                      double fireInterval, double bulletSpeed, int bulletDamage,
                      double attackRange, double preferredDistance) {
        super(x, y, radius, maxHealth, speed, false, fireInterval, bulletSpeed, bulletDamage);
        this.attackRange = attackRange;
        this.preferredDistance = preferredDistance;
        strafeDirection = ((long) Math.floor(x + y) & 1) == 0 ? 1 : -1;
        shooting.delay(0.3 + (Math.sin(x * 0.023 + y * 0.017) + 1) * 0.45);
    }

    public Bullet updateAI(double dt, Unit target, int width, int height) {
        update(dt);
        if (!isAlive() || !target.isAlive()) {
            return null;
        }
        double dx = target.getX() - getX();
        double dy = target.getY() - getY();
        double distance = Math.hypot(dx, dy);
        if (distance > 0.0001) {
            double towardX = dx / distance;
            double towardY = dy / distance;
            double approach = distance > preferredDistance + 35 ? 1 :
                    distance < preferredDistance - 35 ? -1 : 0;
            double strafe = distance < attackRange ? 0.6 * strafeDirection : 0;
            move(towardX * approach - towardY * strafe,
                    towardY * approach + towardX * strafe, dt, width, height);
        }
        aimAt(target.getX(), target.getY());
        return Math.hypot(target.getX() - getX(), target.getY() - getY()) <= attackRange
                ? fireAt(target.getX(), target.getY()) : null;
    }

    public double getAttackRange() { return attackRange; }
}
