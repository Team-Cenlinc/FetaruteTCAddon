package org.fetarute.fetaruteTCAddon.display.pids.map;

import com.bergerkiller.bukkit.common.map.MapColorPalette;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 地图调色板换算：把渲染帧的 RGB 换成地图颜色字节。
 *
 * <p>站台屏配色本身取自调色板，原样命中；线路色来自数据，多半不在调色板上，按 CIELAB 色差取最近的颜色。BKC 自带的取色在饱和色上偏差较大（例如 #E5534B
 * 会落到纯红），所以不用它。
 *
 * <p>换算结果按 RGB 缓存，线程安全。
 */
public final class PidsMapPalette {

  private final byte[] codes;
  private final double[][] labs;
  private final int[] rgbByCode = new int[256];
  private final ConcurrentHashMap<Integer, Byte> nearest = new ConcurrentHashMap<>();

  /**
   * @param codes 调色板字节
   * @param colors 与 {@code codes} 一一对应的颜色（{@code 0xRRGGBB}）
   */
  public PidsMapPalette(byte[] codes, int[] colors) {
    Objects.requireNonNull(codes, "codes");
    Objects.requireNonNull(colors, "colors");
    if (codes.length != colors.length || codes.length == 0) {
      throw new IllegalArgumentException("调色板字节与颜色数量不一致或为空");
    }
    this.codes = codes.clone();
    this.labs = new double[colors.length][];
    Arrays.fill(rgbByCode, -1);
    for (int i = 0; i < colors.length; i++) {
      int rgb = colors[i] & 0xFFFFFF;
      labs[i] = lab(rgb);
      rgbByCode[codes[i] & 0xFF] = rgb;
    }
  }

  /** Minecraft 地图调色板（取自 BKC，不含透明色）。 */
  public static PidsMapPalette minecraft() {
    return Minecraft.PALETTE;
  }

  /** 颜色对应的调色板字节：调色板里有的原样取用，没有的取 CIELAB 色差最小者。 */
  public byte code(int rgb) {
    return nearest.computeIfAbsent(rgb & 0xFFFFFF, this::nearestCode);
  }

  /**
   * 调色板字节对应的颜色。
   *
   * @throws IllegalArgumentException 不是本调色板里的字节
   */
  public int rgb(byte code) {
    int rgb = rgbByCode[code & 0xFF];
    if (rgb < 0) {
      throw new IllegalArgumentException("不在调色板中的颜色字节: " + (code & 0xFF));
    }
    return rgb;
  }

  /**
   * 把整帧换成调色板字节，按行优先排列。
   *
   * @param frame 渲染帧
   * @param out 输出，长度至少为 {@code 宽 × 高}
   */
  public void convert(BufferedImage frame, byte[] out) {
    int width = frame.getWidth();
    int height = frame.getHeight();
    if (out.length < width * height) {
      throw new IllegalArgumentException("输出缓冲区不足: " + out.length + " < " + width * height);
    }
    int[] pixels = pixels(frame);
    FrameColors colors = new FrameColors();
    int last = ~pixels[0] & 0xFFFFFF;
    byte lastCode = 0;
    for (int i = 0; i < width * height; i++) {
      int rgb = pixels[i] & 0xFFFFFF;
      if (rgb != last) {
        last = rgb;
        lastCode = colors.code(rgb);
      }
      out[i] = lastCode;
    }
  }

  /**
   * 帧的像素：渲染器产出的 {@code TYPE_INT_RGB} 整图直接读底层数组（行优先、无偏移），省掉整帧复制与逐像素的颜色模型换算； 其他类型照常 {@code getRGB}。
   */
  private static int[] pixels(BufferedImage frame) {
    int width = frame.getWidth();
    int height = frame.getHeight();
    if (frame.getType() == BufferedImage.TYPE_INT_RGB
        && frame.getRaster().getDataBuffer() instanceof DataBufferInt buffer
        && buffer.getNumBanks() == 1
        && buffer.getOffset() == 0
        && buffer.getData().length == width * height) {
      return buffer.getData();
    }
    return frame.getRGB(0, 0, width, height, null, 0, width);
  }

  /** 一帧里用到的颜色：一帧只有几十种颜色，在这里查过一次就不再查全局缓存（全局缓存要装箱、走并发表）。只在单次换算内使用。 */
  private final class FrameColors {
    private int[] keys = new int[64];
    private byte[] values = new byte[64];
    private boolean[] used = new boolean[64];
    private int size;

    byte code(int rgb) {
      int mask = keys.length - 1;
      int slot = Integer.hashCode(rgb * 0x9E3779B9) & mask;
      while (used[slot]) {
        if (keys[slot] == rgb) {
          return values[slot];
        }
        slot = (slot + 1) & mask;
      }
      byte code = PidsMapPalette.this.code(rgb);
      used[slot] = true;
      keys[slot] = rgb;
      values[slot] = code;
      if (++size * 2 > keys.length) {
        grow();
      }
      return code;
    }

    private void grow() {
      int[] oldKeys = keys;
      byte[] oldValues = values;
      boolean[] oldUsed = used;
      keys = new int[oldKeys.length * 2];
      values = new byte[oldKeys.length * 2];
      used = new boolean[oldKeys.length * 2];
      int mask = keys.length - 1;
      for (int i = 0; i < oldKeys.length; i++) {
        if (oldUsed[i]) {
          int slot = Integer.hashCode(oldKeys[i] * 0x9E3779B9) & mask;
          while (used[slot]) {
            slot = (slot + 1) & mask;
          }
          used[slot] = true;
          keys[slot] = oldKeys[i];
          values[slot] = oldValues[i];
        }
      }
    }
  }

  private byte nearestCode(int rgb) {
    double[] target = lab(rgb);
    int best = 0;
    double bestDistance = Double.MAX_VALUE;
    for (int i = 0; i < labs.length; i++) {
      double distance = distance(target, labs[i]);
      if (distance < bestDistance) {
        bestDistance = distance;
        best = i;
      }
    }
    return codes[best];
  }

  /** CIE76 色差的平方。 */
  static double distance(double[] a, double[] b) {
    double dl = a[0] - b[0];
    double da = a[1] - b[1];
    double db = a[2] - b[2];
    return dl * dl + da * da + db * db;
  }

  /** sRGB（D65）换算到 CIELAB。 */
  static double[] lab(int rgb) {
    double r = linear((rgb >> 16) & 0xFF);
    double g = linear((rgb >> 8) & 0xFF);
    double b = linear(rgb & 0xFF);
    double x = (0.4124564 * r + 0.3575761 * g + 0.1804375 * b) / 0.95047;
    double y = 0.2126729 * r + 0.7151522 * g + 0.0721750 * b;
    double z = (0.0193339 * r + 0.1191920 * g + 0.9503041 * b) / 1.08883;
    double fx = labF(x);
    double fy = labF(y);
    double fz = labF(z);
    return new double[] {116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz)};
  }

  private static double linear(int channel) {
    double c = channel / 255.0;
    return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
  }

  private static double labF(double t) {
    return t > 216.0 / 24389.0 ? Math.cbrt(t) : (24389.0 / 27.0 * t + 16) / 116;
  }

  /** 延迟到首次使用时才读取 BKC 调色板。 */
  private static final class Minecraft {
    private static final PidsMapPalette PALETTE = load();

    private static PidsMapPalette load() {
      int count = Math.min(MapColorPalette.getColorCount(), 256);
      byte[] codes = new byte[count];
      int[] colors = new int[count];
      int size = 0;
      for (int i = 0; i < count; i++) {
        byte code = (byte) i;
        if (!MapColorPalette.isTransparent(code)) {
          codes[size] = code;
          colors[size] = MapColorPalette.getRealColor(code).getRGB() & 0xFFFFFF;
          size++;
        }
      }
      return new PidsMapPalette(Arrays.copyOf(codes, size), Arrays.copyOf(colors, size));
    }
  }
}
