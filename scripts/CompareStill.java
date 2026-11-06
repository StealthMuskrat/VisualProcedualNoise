import java.nio.file.Path;
import javax.imageio.ImageIO;

/** Source-launched verification helper using the JDK's own PNG decoder. */
class CompareStill {
  public static void main(String[] args) throws Exception {
    var a = ImageIO.read(Path.of(args[0]).toFile());
    var b = ImageIO.read(Path.of(args[1]).toFile());
    if (a == null || b == null || a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight())
      throw new AssertionError("Still dimensions or encoding differ");
    int maximum = 0, changed = 0;
    int width = args.length > 2 && args[2].equals("left") ? a.getWidth() / 2 : a.getWidth();
    for (int y = 0; y < a.getHeight(); y++)
      for (int x = 0; x < width; x++) {
        int p = a.getRGB(x, y), q = b.getRGB(x, y);
        if (p != q) changed++;
        for (int shift : new int[] {0, 8, 16, 24})
          maximum = Math.max(maximum, Math.abs(((p >>> shift) & 255) - ((q >>> shift) & 255)));
      }
    long total = (long) width * a.getHeight();
    // Native and texture-backed normalized outputs may round at adjacent 8-bit levels.
    if (maximum > 1 || changed > total * .001)
      throw new AssertionError(
          "Still image differs: maximum channel error="
              + maximum
              + ", changed="
              + changed
              + "/"
              + total);
    System.out.println(
        "Native framebuffer comparison passed: maximum channel error="
            + maximum
            + ", changed="
            + changed
            + "/"
            + total);
  }
}
