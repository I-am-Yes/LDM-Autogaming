package core.graphic;

import core.app.GameInput;
import core.app.GameClock;
import core.app.plugin.mcp.GameControl;
import core.app.command.GameActivity;
import core.app.command.Terminal;
import core.map.World;
import core.utils.combat.Bullet;
import core.utils.unit.Unit;

import java.awt.*;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferStrategy;
import java.awt.image.BufferedImage;
import java.util.concurrent.locks.LockSupport;

/** Active rendering at 120 FPS, with fixed gameplay steps scheduled at the selected speed. */
public final class Render extends Canvas implements Runnable {
    private static final long serialVersionUID = 1L;
    private static final long FRAME_NANOS = 1_000_000_000L / 120;
    private static final double STEP_SECONDS = GameClock.STEP_SECONDS;
    private static final Color BACKGROUND = new Color(73, 77, 82);
    private static final Color PLAYER_COLOR = new Color(106, 220, 255);
    private static final Color ENEMY_COLOR = new Color(255, 132, 121);

    private final World world;
    private final Assets assets;
    private final GameInput input;
    private final GameControl control;
    private final GameActivity activity;
    private final Terminal terminal;
    private volatile boolean running;
    private volatile boolean focused;
    private Thread gameThread;
    private BufferStrategy buffers;
    private int fps;
    private int ups;

    public Render(World world, Assets assets, GameInput input) {
        this(world, assets, input, new GameControl(world, input));
    }

    public Render(World world, Assets assets, GameInput input, GameControl control) {
        this(world, assets, input, control, null, null);
    }

    public Render(World world, Assets assets, GameInput input, GameControl control,
                  GameActivity activity, Terminal terminal) {
        this.world = world;
        this.assets = assets;
        this.input = input;
        this.control = control;
        this.activity = activity;
        this.terminal = terminal;
        setPreferredSize(new Dimension(World.WIDTH, World.HEIGHT));
        setBackground(BACKGROUND);
        setFocusable(true);
        setIgnoreRepaint(true);
        addKeyListener(input);
        addMouseListener(input);
        addMouseMotionListener(input);
        addFocusListener(input);
        addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent event) {
                focused = false;
                input.clear();
            }

            @Override
            public void focusGained(FocusEvent event) {
                focused = true;
            }
        });
        if (terminal != null) {
            addKeyListener(new KeyAdapter() {
                @Override
                public void keyPressed(KeyEvent event) {
                    boolean wasOpen = terminal.isOpen();
                    if (terminal.handleKeyPressed(event)) {
                        event.consume();
                    }
                    if (wasOpen != terminal.isOpen()) {
                        input.setEnabled(!terminal.isOpen());
                    }
                }

                @Override
                public void keyTyped(KeyEvent event) {
                    if (terminal.handleKeyTyped(event)) {
                        event.consume();
                    }
                }
            });
        }
    }

    /** Call after this canvas has been added to a visible window. */
    public synchronized void start() {
        if (running) {
            return;
        }
        createBufferStrategy(2);
        buffers = getBufferStrategy();
        focused = isFocusOwner();
        running = true;
        gameThread = new Thread(this, "battle-game-loop");
        gameThread.start();
    }

    public synchronized void stop() {
        running = false;
        control.close();
        if (gameThread != null) {
            gameThread.interrupt();
            if (Thread.currentThread() != gameThread) {
                try {
                    gameThread.join(250);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    @Override
    public void removeNotify() {
        stop();
        super.removeNotify();
    }

    @Override
    public void run() {
        long previous = System.nanoTime();
        long nextFrame = previous;
        long counterStart = previous;
        GameClock simulationClock = new GameClock();
        int frames = 0;
        int updates = 0;
        try {
            while (running) {
                long now = System.nanoTime();
                int dueUpdates = simulationClock.advance(
                        (now - previous) / 1_000_000_000.0, control.getGameSpeed());
                previous = now;
                for (int i = 0; i < dueUpdates; i++) {
                    control.update(STEP_SECONDS, focused);
                    updates++;
                }

                if (now >= nextFrame) {
                    drawFrame();
                    frames++;
                    nextFrame += FRAME_NANOS;
                    if (nextFrame < now - FRAME_NANOS) {
                        nextFrame = now + FRAME_NANOS;
                    }
                }
                if (now - counterStart >= 1_000_000_000L) {
                    fps = frames;
                    ups = updates;
                    frames = 0;
                    updates = 0;
                    counterStart = now;
                }
                long untilFrame = nextFrame - System.nanoTime();
                if (untilFrame > 0) {
                    LockSupport.parkNanos(untilFrame);
                }
            }
        } finally {
            running = false;
            control.close();
        }
    }

    private void drawFrame() {
        if (!isDisplayable()) {
            running = false;
            return;
        }
        try {
            do {
                do {
                    Graphics2D graphics = (Graphics2D) buffers.getDrawGraphics();
                    try {
                        drawScene(graphics);
                    } finally {
                        graphics.dispose();
                    }
                } while (running && buffers.contentsRestored());
                buffers.show();
                Toolkit.getDefaultToolkit().sync();
            } while (running && buffers.contentsLost());
        } catch (IllegalStateException exception) {
            // The native surface may disappear as the window closes.
            running = false;
        }
    }

    /** Also supports rendering an unchanged world to an offscreen image. */
    public void drawScene(Graphics2D graphics) {
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        graphics.setColor(BACKGROUND);
        graphics.fillRect(0, 0, World.WIDTH, World.HEIGHT);
        graphics.setColor(new Color(255, 255, 255, 12));
        for (int x = 0; x < World.WIDTH; x += 40) {
            graphics.drawLine(x, 0, x, World.HEIGHT);
        }
        for (int y = 0; y < World.HEIGHT; y += 40) {
            graphics.drawLine(0, y, World.WIDTH, y);
        }

        for (Bullet bullet : world.getBullets()) {
            drawSprite(graphics, bullet.isPlayerOwned() ? assets.playerBullet : assets.enemyBullet,
                    bullet.getX(), bullet.getY(), bullet.getAngle(), 18);
        }
        for (Unit enemy : world.getEnemies()) {
            drawUnit(graphics, enemy, assets.enemy, ENEMY_COLOR);
        }
        if (world.getPlayer().isAlive()) {
            drawUnit(graphics, world.getPlayer(), assets.player, PLAYER_COLOR);
        }
        drawCrosshair(graphics);
        drawHud(graphics);
        if (world.isGameOver()) {
            drawOverlay(graphics, "UNIT DESTROYED", "Press R to start again");
        } else if (!focused && isDisplayable() && !control.isAgentControlled()) {
            drawOverlay(graphics, "PAUSED", "Click the battlefield to return");
        } else if (world.isPaused()) {
            drawOverlay(graphics, "PAUSED", "Press P to continue");
        } else if (control.isWaitingForAction()) {
            drawOverlay(graphics, "AI CONTROL", "Waiting for the next action");
        }
        if (terminal != null) {
            terminal.draw(graphics, World.WIDTH, World.HEIGHT);
        }
    }

    private void drawUnit(Graphics2D graphics, Unit unit, BufferedImage image, Color color) {
        int x = (int) Math.round(unit.getX());
        int y = (int) Math.round(unit.getY());
        int radius = (int) unit.getRadius();
        graphics.setColor(new Color(0, 0, 0, 55));
        graphics.fillOval(x - radius, y + 7 - radius / 2, radius * 2, radius);
        // The bot texture points left; the player texture points right.
        double spriteAngle = unit.getAngle() + (unit.isPlayerControlled() ? 0 : Math.PI);
        drawSprite(graphics, image, unit.getX(), unit.getY(), spriteAngle, radius * 2.8);

        int barWidth = 38;
        int barY = y - radius - 13;
        graphics.setColor(new Color(24, 28, 32));
        graphics.fillRoundRect(x - barWidth / 2, barY, barWidth, 5, 3, 3);
        graphics.setColor(color);
        int filled = (int) Math.round(barWidth * unit.getHealth() / (double) unit.getMaxHealth());
        graphics.fillRoundRect(x - barWidth / 2, barY, filled, 5, 3, 3);
    }

    private void drawSprite(Graphics2D graphics, BufferedImage image,
                            double x, double y, double angle, double width) {
        AffineTransform original = graphics.getTransform();
        graphics.translate(x, y);
        graphics.rotate(angle);
        double height = width * image.getHeight() / image.getWidth();
        graphics.drawImage(image, (int) (-width / 2), (int) (-height / 2),
                (int) width, (int) height, null);
        graphics.setTransform(original);
    }

    private void drawCrosshair(Graphics2D graphics) {
        if ((!focused && !control.isAgentControlled()) || world.isGameOver()
                || terminal != null && terminal.isOpen()) {
            return;
        }
        int x = (int) control.getAimX();
        int y = (int) control.getAimY();
        graphics.setColor(new Color(222, 244, 250, 180));
        graphics.setStroke(new BasicStroke(1));
        graphics.drawOval(x - 7, y - 7, 14, 14);
        graphics.drawLine(x - 12, y, x - 5, y);
        graphics.drawLine(x + 5, y, x + 12, y);
        graphics.drawLine(x, y - 12, x, y - 5);
        graphics.drawLine(x, y + 5, x, y + 12);
    }

    private void drawHud(Graphics2D graphics) {
        graphics.setColor(new Color(24, 28, 32, 220));
        graphics.fillRect(0, 0, World.WIDTH, 55);
        graphics.fillRect(0, World.HEIGHT - 34, World.WIDTH, 34);
        graphics.setColor(Color.WHITE);
        graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 18));
        graphics.drawString("BATTLEFIELD", 20, 34);
        graphics.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 15));
        graphics.setColor(PLAYER_COLOR);
        graphics.drawString("HP  " + world.getPlayer().getHealth() + " / "
                + world.getPlayer().getMaxHealth(), 200, 33);
        graphics.setColor(Color.WHITE);
        graphics.drawString("Wave " + world.getWave(), 365, 33);
        graphics.drawString("Score " + world.getScore(), 475, 33);
        graphics.drawString("Enemies " + world.getEnemies().size(), 595, 33);
        graphics.setColor(new Color(186, 194, 201));
        graphics.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        graphics.drawString(fps + " FPS / " + ups + " UPS", World.WIDTH - 140, 32);
        if (activity != null) {
            drawMcpStatus(graphics);
        }
        if (terminal == null || !terminal.isOpen()) {
            graphics.drawString("WASD / Arrows: move     Mouse: aim     Hold LMB / Space: fire"
                    + "     P: pause     R: restart     Esc: manual control     T or /: chat",
                    20, World.HEIGHT - 12);
        }
    }

    private void drawMcpStatus(Graphics2D graphics) {
        StringBuilder text = new StringBuilder(activity.getServerStatus()).append(" | ");
        java.util.List<GameActivity.ClientView> clients = activity.getClients();
        if (clients.isEmpty()) {
            text.append("No MCP clients");
        } else {
            text.append("Clients: ");
            for (int i = 0; i < clients.size(); i++) {
                if (i > 0) {
                    text.append(", ");
                }
                GameActivity.ClientView client = clients.get(i);
                text.append(client.name());
                if (!client.version().isBlank()) {
                    text.append(" v").append(client.version());
                }
                text.append(" (").append(client.status()).append(')');
            }
        }
        if (activity.getRunningCount() > 0) {
            text.append(" | Running: ").append(String.join(", ", activity.runningCommandDescriptions()));
        }
        graphics.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
        graphics.setColor(new Color(174, 216, 202));
        String status = text.toString();
        while (status.length() > 3 && graphics.getFontMetrics().stringWidth(status) > World.WIDTH - 40) {
            status = status.substring(0, status.length() - 4) + "...";
        }
        graphics.drawString(status, 20, 49);
        graphics.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        graphics.setColor(new Color(186, 194, 201));
    }

    private void drawOverlay(Graphics2D graphics, String title, String subtitle) {
        graphics.setColor(new Color(15, 19, 23, 180));
        graphics.fillRect(0, 55, World.WIDTH, World.HEIGHT - 89);
        graphics.setColor(Color.WHITE);
        graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 38));
        graphics.drawString(title, (World.WIDTH - graphics.getFontMetrics().stringWidth(title)) / 2,
                World.HEIGHT / 2 - 10);
        graphics.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 18));
        graphics.setColor(new Color(200, 211, 220));
        graphics.drawString(subtitle,
                (World.WIDTH - graphics.getFontMetrics().stringWidth(subtitle)) / 2,
                World.HEIGHT / 2 + 28);
    }
}
