package core.utils.combat;

import core.utils.unit.Unit;

/** A projectile with swept collision detection between its previous and current positions. */
public final class Bullet {
    private double x;
    private double y;
    private double previousX;
    private double previousY;
    private final double angle;
    private final double velocityX;
    private final double velocityY;
    private final double radius = 3.5;
    private final int damage;
    private final boolean playerOwned;
    private double remainingLife = 3;

    public Bullet(double x, double y, double angle, double speed, int damage, boolean playerOwned) {
        this.x = previousX = x;
        this.y = previousY = y;
        this.angle = angle;
        velocityX = Math.cos(angle) * speed;
        velocityY = Math.sin(angle) * speed;
        this.damage = damage;
        this.playerOwned = playerOwned;
    }

    public void update(double dt) {
        previousX = x;
        previousY = y;
        double step = Math.max(0, dt);
        x += velocityX * step;
        y += velocityY * step;
        remainingLife -= step;
    }

    public boolean hits(Unit unit) {
        if (!unit.isAlive() || playerOwned == unit.isPlayerControlled()) {
            return false;
        }
        double dx = x - previousX;
        double dy = y - previousY;
        double lengthSquared = dx * dx + dy * dy;
        double t = lengthSquared == 0 ? 0 :
                ((unit.getX() - previousX) * dx + (unit.getY() - previousY) * dy) / lengthSquared;
        t = Math.max(0, Math.min(1, t));
        double offsetX = unit.getX() - (previousX + t * dx);
        double offsetY = unit.getY() - (previousY + t * dy);
        double hitRadius = radius + unit.getRadius();
        return offsetX * offsetX + offsetY * offsetY <= hitRadius * hitRadius;
    }

    public boolean isExpired() {
        return remainingLife <= 0;
    }

    public boolean isOutside(int width, int height) {
        return x < -radius || y < -radius || x > width + radius || y > height + radius;
    }

    public double getX() { return x; }
    public double getY() { return y; }
    public double getPreviousX() { return previousX; }
    public double getPreviousY() { return previousY; }
    public double getVelocityX() { return velocityX; }
    public double getVelocityY() { return velocityY; }
    public double getAngle() { return angle; }
    public double getRadius() { return radius; }
    public int getDamage() { return damage; }
    public boolean isPlayerOwned() { return playerOwned; }
}
