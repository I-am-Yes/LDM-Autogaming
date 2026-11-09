package core.utils.unit.enemy;

import core.utils.unit.BotUnit;

/** Long-range enemy built on the reusable bot behavior. */
public final class AnglerUnit extends BotUnit {
    public AnglerUnit(double x, double y) {
        super(x, y, 18, 50, 85, 1.2, 270, 10, 700, 300);
    }
}
