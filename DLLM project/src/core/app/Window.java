package core.app;

import core.graphic.Assets;
import core.graphic.Render;
import core.map.World;
import core.app.plugin.mcp.GameControl;
import core.app.plugin.mcp.MCP;
import core.app.command.GameActivity;
import core.app.command.Terminal;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

public final class Window extends JFrame {
    private static final long serialVersionUID = 1L;

    private final JProgressBar loadingProgress = new JProgressBar(0, 100);
    private SwingWorker<Assets, Void> loader;
    private Render renderer;
    private GameControl control;
    private MCP mcp;
    private final GameActivity activity = new GameActivity();
    private Terminal terminal;
    private JComboBox<Double> speedSelector;
    private Timer speedRefresh;
    private boolean syncingSpeedSelection;
    private boolean closed;

    public Window() {
        super("Decision Models Battle");
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        setResizable(false);
        setContentPane(createLoadingScreen());
        pack();
        setLocationRelativeTo(null);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent event) {
                closed = true;
                if (speedRefresh != null) {
                    speedRefresh.stop();
                }
                if (loader != null) {
                    loader.cancel(true);
                }
                if (control != null) {
                    control.close();
                }
                if (mcp != null) {
                    mcp.close();
                }
                if (renderer != null) {
                    renderer.stop();
                }
                if (terminal != null) {
                    terminal.close();
                }
            }
        });
    }

    private JPanel createLoadingScreen() {
        JPanel screen = new JPanel(new BorderLayout());
        screen.setPreferredSize(new Dimension(World.WIDTH, World.HEIGHT));
        screen.setBackground(new Color(47, 50, 54));

        JPanel content = new JPanel();
        content.setOpaque(false);
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBorder(BorderFactory.createEmptyBorder(230, 250, 0, 250));

        JLabel title = new JLabel("TINY BATTLE");
        title.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 36));
        title.setForeground(Color.WHITE);
        title.setAlignmentX(CENTER_ALIGNMENT);
        content.add(title);
        content.add(Box.createVerticalStrut(24));

        loadingProgress.setStringPainted(true);
        loadingProgress.setString("Loading textures...");
        loadingProgress.setMaximumSize(new Dimension(460, 26));
        loadingProgress.setAlignmentX(CENTER_ALIGNMENT);
        content.add(loadingProgress);
        screen.add(content, BorderLayout.CENTER);
        return screen;
    }

    public void open() {
        setVisible(true);
        loader = new SwingWorker<>() {
            @Override
            protected Assets doInBackground() throws Exception {
                return Assets.load(this::setProgress);
            }

            @Override
            protected void done() {
                if (isCancelled() || closed) {
                    return;
                }
                try {
                    Assets assets = get();
                    GameInput input = new GameInput();
                    World world = new World();
                    control = new GameControl(world, input);
                    terminal = new Terminal(activity, control, () -> {
                        input.setEnabled(true);
                        renderer.requestFocusInWindow();
                    });
                    renderer = new Render(world, assets, input, control, activity, terminal);
                    getContentPane().removeAll();
                    getContentPane().add(createSpeedToolbar(), BorderLayout.NORTH);
                    getContentPane().add(renderer, BorderLayout.CENTER);
                    revalidate();
                    pack();
                    setLocationRelativeTo(null);
                    renderer.requestFocusInWindow();
                    renderer.start();
                    startMcp();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    dispose();
                } catch (ExecutionException exception) {
                    JOptionPane.showMessageDialog(Window.this,
                            "Could not load game assets:\n" + exception.getCause().getMessage(),
                            "Asset loading failed", JOptionPane.ERROR_MESSAGE);
                    dispose();
                }
            }
        };
        loader.addPropertyChangeListener(event -> {
            if ("progress".equals(event.getPropertyName())) {
                int progress = (Integer) event.getNewValue();
                loadingProgress.setValue(progress);
                loadingProgress.setString("Loading textures... " + progress + "%");
            }
        });
        loader.execute();
    }

    /**
     * Creates the speed selector and keeps it synchronized with terminal and MCP changes.
     * The toolbar is outside the Canvas so choosing a speed cannot fire a weapon.
     *
     * @return the compact toolbar displayed above the battlefield
     */
    private JPanel createSpeedToolbar() {
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 4));
        toolbar.setBackground(new Color(28, 32, 36));
        JLabel label = new JLabel("Game speed");
        label.setForeground(new Color(226, 234, 240));
        toolbar.add(label);

        DefaultComboBoxModel<Double> choices = new DefaultComboBoxModel<>(
                new Double[]{1.0, 2.0, 4.0, 8.0, 16.0});
        speedSelector = new JComboBox<>(choices);
        speedSelector.setPreferredSize(new Dimension(92, 24));
        speedSelector.setBackground(new Color(47, 53, 59));
        speedSelector.setForeground(Color.WHITE);
        speedSelector.setToolTipText("Speeds up both sides. Each action still advances the requested number of steps.");
        speedSelector.setRenderer(new DefaultListCellRenderer() {
            private static final long serialVersionUID = 1L;

            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                          boolean selected, boolean focused) {
                super.getListCellRendererComponent(list, value, index, selected, focused);
                if (value instanceof Number number) {
                    setText(formatSpeed(number.doubleValue()));
                }
                return this;
            }
        });
        speedSelector.addActionListener(event -> {
            if (!syncingSpeedSelection && !closed && speedSelector.getSelectedItem() instanceof Double speed) {
                requestSpeedChange(speed);
            }
        });
        toolbar.add(speedSelector);
        syncSpeedSelection();
        speedRefresh = new Timer(200, event -> syncSpeedSelection());
        speedRefresh.start();
        return toolbar;
    }

    /**
     * Queues a GUI speed change without blocking the event-dispatch thread.
     *
     * @param speed selected simulation-speed multiplier
     */
    private void requestSpeedChange(double speed) {
        long token = activity.commandStarted("GUI", "game speed " + formatSpeed(speed));
        speedSelector.setEnabled(false);
        control.setGameSpeed(speed).whenComplete((state, error) -> {
            Throwable failure = error;
            while (failure instanceof CompletionException && failure.getCause() != null) {
                failure = failure.getCause();
            }
            activity.commandFinished(token, failure == null,
                    failure == null ? "Game speed set to " + formatSpeed(speed) : failure.getMessage());
            SwingUtilities.invokeLater(() -> {
                if (!closed) {
                    speedSelector.setEnabled(true);
                    syncSpeedSelection();
                    renderer.requestFocusInWindow();
                }
            });
        });
    }

    /** Reflects the current speed on the EDT without creating another change request. */
    private void syncSpeedSelection() {
        if (closed || speedSelector == null) {
            return;
        }
        double speed = control.getGameSpeed();
        if (!(speedSelector.getSelectedItem() instanceof Double selected)
                || Double.compare(selected, speed) != 0) {
            syncingSpeedSelection = true;
            try {
                // A model can display a custom command value even when it is not a preset.
                speedSelector.getModel().setSelectedItem(speed);
            } finally {
                syncingSpeedSelection = false;
            }
        }
    }

    /**
     * Formats whole and fractional speed multipliers for the selector and activity log.
     *
     * @param speed simulation-speed multiplier
     * @return the multiplier followed by a multiplication sign
     */
    private static String formatSpeed(double speed) {
        return String.format(Locale.ROOT, "%s\u00d7", speed == Math.rint(speed)
                ? Long.toString((long) speed) : Double.toString(speed));
    }

    private void startMcp() {
        try {
            mcp = new MCP(control, Integer.getInteger("battle.mcp.port", MCP.DEFAULT_PORT), activity);
            mcp.start();
        } catch (IOException | IllegalArgumentException exception) {
            if (mcp != null) {
                mcp.close();
            }
            activity.setServerStatus("MCP unavailable");
            activity.log("Could not start game MCP server: " + exception.getMessage()
                    + ". Set -Dbattle.mcp.port=8766 to use another port.");
        }
    }

}
