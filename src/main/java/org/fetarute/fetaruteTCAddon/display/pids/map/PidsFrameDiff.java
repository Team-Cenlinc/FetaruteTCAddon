package org.fetarute.fetaruteTCAddon.display.pids.map;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 两帧之间按地图块找出变化的区域。
 *
 * <p>地图数据包一次只带一个矩形，按块取变化像素的外接矩形：倒计时只改几个数字时，每块只是那几个数字的范围；整页切换时每块都是满块。
 *
 * <p>BKC 每个 tick 把同一块显示上的所有写入合成一个外接矩形，再给它压到的每块地图发包。散在屏幕两头的两个数字若在同一 tick 写入， 中间没变的地图也会被带上；{@link
 * #writeTogether} 判断该一次写完还是分 tick 逐块写。
 */
public final class PidsFrameDiff {

  /** 地图边长。 */
  public static final int TILE = 128;

  private PidsFrameDiff() {}

  /** 帧内的矩形（像素坐标）。 */
  public record Region(int x, int y, int width, int height) {}

  /**
   * @param before 上一帧；为空表示还没画过，每块整块写入
   * @param after 当前帧
   * @param width 帧宽（128 的整数倍）
   * @param height 帧高（128 的整数倍）
   * @return 每块变化像素的外接矩形，按块的行优先顺序；无变化为空列表
   */
  public static List<Region> changed(byte[] before, byte[] after, int width, int height) {
    int tilesX = width / TILE;
    int tilesY = height / TILE;
    if (before == null) {
      List<Region> all = new ArrayList<>(tilesX * tilesY);
      for (int ty = 0; ty < tilesY; ty++) {
        for (int tx = 0; tx < tilesX; tx++) {
          all.add(new Region(tx * TILE, ty * TILE, TILE, TILE));
        }
      }
      return all;
    }
    int tiles = tilesX * tilesY;
    int[] minX = new int[tiles];
    int[] minY = new int[tiles];
    int[] maxX = new int[tiles];
    int[] maxY = new int[tiles];
    Arrays.fill(minX, Integer.MAX_VALUE);
    Arrays.fill(maxX, -1);
    for (int y = 0; y < height; y++) {
      int row = y * width;
      int tileRow = (y / TILE) * tilesX;
      for (int x = 0; x < width; x++) {
        if (before[row + x] != after[row + x]) {
          int tile = tileRow + x / TILE;
          if (maxX[tile] < 0) {
            minY[tile] = y;
          }
          minX[tile] = Math.min(minX[tile], x);
          maxX[tile] = Math.max(maxX[tile], x);
          maxY[tile] = y;
        }
      }
    }
    List<Region> regions = new ArrayList<>();
    for (int tile = 0; tile < tiles; tile++) {
      if (maxX[tile] >= 0) {
        regions.add(
            new Region(
                minX[tile], minY[tile], maxX[tile] - minX[tile] + 1, maxY[tile] - minY[tile] + 1));
      }
    }
    return regions;
  }

  /** 这些区域是否在同一 tick 写完：外接矩形面积不超过各区域面积之和的两倍时是（整页切换、相邻几块）， 否则分 tick 逐块写（屏幕两头各改一处）。 */
  public static boolean writeTogether(List<Region> regions) {
    if (regions.size() <= 1) {
      return true;
    }
    int minX = Integer.MAX_VALUE;
    int minY = Integer.MAX_VALUE;
    int maxX = Integer.MIN_VALUE;
    int maxY = Integer.MIN_VALUE;
    long area = 0;
    for (Region region : regions) {
      minX = Math.min(minX, region.x());
      minY = Math.min(minY, region.y());
      maxX = Math.max(maxX, region.x() + region.width());
      maxY = Math.max(maxY, region.y() + region.height());
      area += (long) region.width() * region.height();
    }
    long union = (long) (maxX - minX) * (maxY - minY);
    return union <= area * 2;
  }

  /** 从整帧里取出一个矩形的像素，行优先。 */
  public static byte[] crop(byte[] frame, int frameWidth, Region region) {
    byte[] out = new byte[region.width() * region.height()];
    for (int row = 0; row < region.height(); row++) {
      System.arraycopy(
          frame,
          (region.y() + row) * frameWidth + region.x(),
          out,
          row * region.width(),
          region.width());
    }
    return out;
  }
}
