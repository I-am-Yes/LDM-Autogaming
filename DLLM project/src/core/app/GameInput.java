package core.app;

import core.map.World;

import java.awt.event.*;
import java.util.HashSet;
import java.util.Set;

/** Input is written by AWT and read by the game thread. */
public final class GameInput extends KeyAdapter
        implements MouseListener, MouseMotionListener, FocusListener {
    private final Set<Integer> pressedKeys = new HashSet<>();
    private volatile double aimX = World.WIDTH * 0.75;
    private volatile double aimY = World.HEIGHT * 0.5;
    private volatile boolean mouseShooting;
    private boolean pauseRequested;
    private boolean restartRequested;
    private boolean releaseControlRequested;
    private volatile boolean enabled = true;

    @Override
    public synchronized void keyPressed(KeyEvent event) {
        if (!enabled) {
            return;
        }
        if (pressedKeys.add(event.getKeyCode())) {
            if (event.getKeyCode() == KeyEvent.VK_P) {
                pauseRequested = true;
            } else if (event.getKeyCode() == KeyEvent.VK_R) {
                restartRequested = true;
            } else if (event.getKeyCode() == KeyEvent.VK_ESCAPE) {
                releaseControlRequested = true;
            }
        }
    }

    @Override
    public synchronized void keyReleased(KeyEvent event) {
        pressedKeys.remove(event.getKeyCode());
    }

    public synchronized int getMoveX() {
        if (!enabled) {
            return 0;
        }
        return (isDown(KeyEvent.VK_D, KeyEvent.VK_RIGHT) ? 1 : 0)
                - (isDown(KeyEvent.VK_A, KeyEvent.VK_LEFT) ? 1 : 0);
    }

    public synchronized int getMoveY() {
        if (!enabled) {
            return 0;
        }
        return (isDown(KeyEvent.VK_S, KeyEvent.VK_DOWN) ? 1 : 0)
                - (isDown(KeyEvent.VK_W, KeyEvent.VK_UP) ? 1 : 0);
    }

    private boolean isDown(int first, int second) {
        return pressedKeys.contains(first) || pressedKeys.contains(second);
    }

    public synchronized boolean isShooting() {
        return enabled && (mouseShooting || pressedKeys.contains(KeyEvent.VK_SPACE));
    }

    public double getAimX() {
        return aimX;
    }

    public double getAimY() {
        return aimY;
    }

    public synchronized boolean consumePause() {
        boolean requested = pauseRequested;
        pauseRequested = false;
        return requested;
    }

    public synchronized boolean consumeRestart() {
        boolean requested = restartRequested;
        restartRequested = false;
        return requested;
    }

    public synchronized boolean consumeReleaseControl() {
        boolean requested = releaseControlRequested;
        releaseControlRequested = false;
        return requested;
    }

    public synchronized void clear() {
        pressedKeys.clear();
        mouseShooting = false;
        pauseRequested = false;
        restartRequested = false;
        releaseControlRequested = false;
    }

    public synchronized void setEnabled(boolean enabled) {
        this.enabled = enabled;
        clear();
    }

    private void updateAim(MouseEvent event) {
        aimX = event.getX();
        aimY = event.getY();
    }

    @Override
    public void mousePressed(MouseEvent event) {
        if (!enabled) {
            return;
        }
        event.getComponent().requestFocusInWindow();
        updateAim(event);
        if (event.getButton() == MouseEvent.BUTTON1) {
            mouseShooting = true;
        }
    }

    @Override
    public void mouseReleased(MouseEvent event) {
        if (event.getButton() == MouseEvent.BUTTON1) {
            mouseShooting = false;
        }
    }

    @Override
    public void mouseMoved(MouseEvent event) {
        updateAim(event);
    }

    @Override
    public void mouseDragged(MouseEvent event) {
        updateAim(event);
    }

    @Override
    public void mouseExited(MouseEvent event) {
        mouseShooting = false;
    }

    @Override
    public void focusLost(FocusEvent event) {
        clear();
    }

    @Override
    public void mouseClicked(MouseEvent event) { }

    @Override
    public void mouseEntered(MouseEvent event) {
        updateAim(event);
    }

    @Override
    public void focusGained(FocusEvent event) { }
}
