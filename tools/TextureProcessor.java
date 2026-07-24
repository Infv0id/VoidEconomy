import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;

public final class TextureProcessor {
    public static void main(String[] args) throws Exception {
        BufferedImage source = ImageIO.read(new File(args[0]));
        BufferedImage keyed = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_ARGB);
        int minX = source.getWidth(), minY = source.getHeight(), maxX = -1, maxY = -1;
        for (int y = 0; y < source.getHeight(); y++) {
            for (int x = 0; x < source.getWidth(); x++) {
                int rgb = source.getRGB(x, y);
                int r = (rgb >>> 16) & 255, g = (rgb >>> 8) & 255, b = rgb & 255;
                boolean key = r > 220 && b > 210 && g < 90 && (r + b - g * 2) > 330;
                if (!key) {
                    keyed.setRGB(x, y, 0xFF000000 | (rgb & 0x00FFFFFF));
                    minX = Math.min(minX, x); minY = Math.min(minY, y);
                    maxX = Math.max(maxX, x); maxY = Math.max(maxY, y);
                }
            }
        }
        if (maxX < minX || maxY < minY) throw new IllegalStateException("No foreground pixels found");
        BufferedImage cropped = keyed.getSubimage(minX, minY, maxX - minX + 1, maxY - minY + 1);
        BufferedImage output = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        int targetWidth = 30;
        int targetHeight = Math.max(1, (int) Math.round(cropped.getHeight() * (targetWidth / (double) cropped.getWidth())));
        int x = (32 - targetWidth) / 2, y = (32 - targetHeight) / 2;
        Graphics2D graphics = output.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        graphics.drawImage(cropped, x, y, targetWidth, targetHeight, null);
        graphics.dispose();
        ImageIO.write(output, "png", new File(args[1]));
    }
}
