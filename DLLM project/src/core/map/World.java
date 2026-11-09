package core.map;

import core.app.GameInput;
import core.utils.combat.Bullet;
import core.utils.unit.enemy.AnglerUnit;
import core.utils.unit.player.PlayerUnit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

/** A small, open arena. All simulation state belongs to the game thread. */
public final class World {
    public static final int WIDTH = 960;
    public static final int HEIGHT = 640;

    private final Random random = new Random();
    private final List<AnglerUnit> enemies = new ArrayList<>();
    private final List<Bullet> bullets = new ArrayList<>();
    private final List<AnglerUnit> enemyView = Collections.unmodifiableList(enemies);
    private final List<Bullet> bulletView = Collections.unmodifiableList(bullets);
    private PlayerUnit player;
    private int score;
    private int wave;
    private double nextWaveDelay;
    private boolean gameOver;
    private boolean paused;

    public World() {
        restart();
    }

    public void restart() {
        player = new PlayerUnit(WIDTH / 2.0, HEIGHT / 2.0);
        enemies.clear();
        bullets.clear();
        score = 0;
        wave = 1;
        nextWaveDelay = 1.5;
        gameOver = false;
        paused = false;
        spawnWave();
    }

    public void update(double dt, GameInput input) {
        if (input.consumeRestart()) {
            restart();
            input.clear();
            return;
        }
        if (input.consumePause() && !gameOver) {
            paused = !paused;
        }
        update(dt, input.getMoveX(), input.getMoveY(), input.getAimX(), input.getAimY(),
                input.isShooting());
    }

    public void update(double dt, double moveX, double moveY,
                       double aimX, double aimY, boolean shooting) {
        if (paused || gameOver) {
            return;
        }

        player.update(dt);
        player.move(moveX, moveY, dt, WIDTH, HEIGHT);
        player.aimAt(aimX, aimY);
        if (shooting) {
            addBullet(player.fireAt(aimX, aimY));
        }
        for (AnglerUnit enemy : enemies) {
            addBullet(enemy.updateAI(dt, player, WIDTH, HEIGHT));
        }

        updateBullets(dt);
        enemies.removeIf(enemy -> !enemy.isAlive());
        gameOver = !player.isAlive();

        if (!gameOver && enemies.isEmpty()) {
            nextWaveDelay -= dt;
            if (nextWaveDelay <= 0) {
                wave++;
                spawnWave();
                nextWaveDelay = 1.5;
            }
        }
    }

    private void addBullet(Bullet bullet) {
        if (bullet != null) {
            bullets.add(bullet);
        }
    }

    private void updateBullets(double dt) {
        Iterator<Bullet> iterator = bullets.iterator();
        while (iterator.hasNext()) {
            Bullet bullet = iterator.next();
            bullet.update(dt);
            boolean hit = false;
            if (bullet.isPlayerOwned()) {
                for (AnglerUnit enemy : enemies) {
                    if (enemy.isAlive() && bullet.hits(enemy)) {
                        enemy.damage(bullet.getDamage());
                        if (!enemy.isAlive()) {
                            score += 100;
                        }
                        hit = true;
                        break;
                    }
                }
            } else if (player.isAlive() && bullet.hits(player)) {
                player.damage(bullet.getDamage());
                hit = true;
            }
            if (hit || bullet.isExpired() || bullet.isOutside(WIDTH, HEIGHT)) {
                iterator.remove();
            }
        }
    }

    private void spawnWave() {
        int count = Math.min(3 + (wave - 1) * 2, 9);
        for (int i = 0; i < count; i++) {
            double x = 45;
            double y = 45;
            for (int attempt = 0; attempt < 80; attempt++) {
                x = 45 + random.nextDouble() * (WIDTH - 90);
                y = 45 + random.nextDouble() * (HEIGHT - 90);
                switch (random.nextInt(4)) {
                    case 0: x = 45; break;
                    case 1: x = WIDTH - 45; break;
                    case 2: y = 45; break;
                    default: y = HEIGHT - 45; break;
                }
                if (Math.hypot(x - player.getX(), y - player.getY()) >= 260
                        && isSpawnClear(x, y)) {
                    break;
                }
            }
            enemies.add(new AnglerUnit(x, y));
        }
    }

    private boolean isSpawnClear(double x, double y) {
        for (AnglerUnit enemy : enemies) {
            if (Math.hypot(x - enemy.getX(), y - enemy.getY()) < 70) {
                return false;
            }
        }
        return true;
    }

    public PlayerUnit getPlayer() {
        return player;
    }

    public List<AnglerUnit> getEnemies() {
        return enemyView;
    }

    public List<Bullet> getBullets() {
        return bulletView;
    }

    public int getScore() {
        return score;
    }

    public int getWave() {
        return wave;
    }

    public boolean isGameOver() {
        return gameOver;
    }

    public boolean isPaused() {
        return paused;
    }

    public void setPaused(boolean paused) {
        this.paused = paused && !gameOver;
    }
}
