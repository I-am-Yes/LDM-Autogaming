package core.utils.skill;

import core.utils.combat.Bullet;
import core.utils.unit.Unit;

/** Fires one projectile when its cooldown is ready. */
public final class ShootingSkill extends Skills {
    private final double bulletSpeed;
    private final int damage;

    public ShootingSkill(double cooldown, double bulletSpeed, int damage) {
        super(cooldown);
        this.bulletSpeed = bulletSpeed;
        this.damage = damage;
    }

    public Bullet fire(Unit owner, double targetX, double targetY) {
        double dx = targetX - owner.getX();
        double dy = targetY - owner.getY();
        if (!owner.isAlive() || !isReady() || Math.hypot(dx, dy) < 0.0001) {
            return null;
        }
        owner.aimAt(targetX, targetY);
        double angle = owner.getAngle();
        double muzzleDistance = owner.getRadius() + 5;
        startCooldown();
        return new Bullet(owner.getX() + Math.cos(angle) * muzzleDistance,
                owner.getY() + Math.sin(angle) * muzzleDistance,
                angle, bulletSpeed, damage, owner.isPlayerControlled());
    }
}
