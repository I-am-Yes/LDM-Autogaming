package core.graphic;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.IntConsumer;

/** The textures loaded before the game loop starts. */
public final class Assets {
    public final BufferedImage player;
    public final BufferedImage enemy;
    public final BufferedImage playerBullet;
    public final BufferedImage enemyBullet;

    private Assets(BufferedImage player, BufferedImage enemy,
                   BufferedImage playerBullet, BufferedImage enemyBullet) {
        this.player = player;
        this.enemy = enemy;
        this.playerBullet = playerBullet;
        this.enemyBullet = enemyBullet;
    }

    public static Assets load(IntConsumer progress) throws IOException {
        Path root = findAssets();
        progress.accept(0);
        BufferedImage player = read(root, "units/player/player_unit.png");
        progress.accept(25);
        BufferedImage enemy = read(root, "units/bot/bot_unit_01.png");
        progress.accept(50);
        BufferedImage playerBullet = read(root, "particles/bullets/normal_bullet.png");
        progress.accept(75);
        BufferedImage enemyBullet = read(root, "particles/bullets/traingle_bullet.png");
        progress.accept(100);
        return new Assets(player, enemy, playerBullet, enemyBullet);
    }

    private static BufferedImage read(Path root, String relativePath) throws IOException {
        Path file = root.resolve(relativePath);
        if (!Files.isRegularFile(file)) {
            throw new IOException("Missing game texture: " + file);
        }
        BufferedImage image = ImageIO.read(file.toFile());
        if (image == null) {
            throw new IOException("Cannot read game texture: " + file);
        }
        return image;
    }

    private static Path findAssets() throws IOException {
        Path root = findFrom(Path.of("").toAbsolutePath());
        if (root != null) {
            return root;
        }
        try {
            Path location = Path.of(Assets.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            root = findFrom(Files.isDirectory(location) ? location : location.getParent());
            if (root != null) {
                return root;
            }
        } catch (Exception exception) {
            throw new IOException("Cannot locate the game's assets folder.", exception);
        }
        throw new IOException("Cannot find the assets folder. Keep it beside the src folder.");
    }

    private static Path findFrom(Path directory) {
        for (Path current = directory; current != null; current = current.getParent()) {
            Path candidate = current.resolve("assets");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
        }
        return null;
    }
}
