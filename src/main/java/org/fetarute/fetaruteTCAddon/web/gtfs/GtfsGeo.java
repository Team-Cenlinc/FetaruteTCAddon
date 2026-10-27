package org.fetarute.fetaruteTCAddon.web.gtfs;

import java.util.Locale;

/**
 * 世界坐标到 GTFS 经纬度的换算。
 *
 * <p>插件没有真实地理坐标，原点固定在西经 30°、赤道上的大西洋洋面（见 {@link #ORIGIN_LATITUDE} / {@link
 * #ORIGIN_LONGITUDE}），一格算一米， {@code +x} 向东、{@code +z} 向南，按赤道上的局部等距投影：
 *
 * <pre>
 * lat =  0° - z / R × 180 / π
 * lon = -30° + x / R × 180 / π        R = 6378137
 * </pre>
 *
 * <p>两个方向都严格是一格一米；现有线网横跨约 5 km，误差可以忽略。GTFS 的 CSV 保留 7 位小数（约 1 cm）；GTFS-Realtime 的经纬度是
 * float32，原点附近精度在毫米级。多个世界的方块坐标会重叠，这是已知取舍：消费者只需要相对位置。
 *
 * <p><b>为什么不是 {@code (0, 0)}</b>：MobilityData 的 gtfs-validator 8.x 对 |lat| ≤ 1 且 |lon| ≤ 1 的停靠点报
 * {@code point_near_origin}（ERROR），固定原点在 (0, 0) 会让整包校验失败。坐标只表达相对位置，把原点挪到一片没有真实公交数据的洋面不影响任何消费者；
 * 偏移量写死，不开放配置。
 */
public final class GtfsGeo {

  /** WGS-84 赤道半径（米）。 */
  private static final double EARTH_RADIUS_METERS = 6_378_137.0;

  private static final double RADIANS_TO_DEGREES = 180.0 / Math.PI;

  /** 原点纬度（度）。赤道上的大西洋洋面，避开校验器的 {@code point_near_origin}。 */
  public static final double ORIGIN_LATITUDE = 0.0;

  /** 原点经度（度）。 */
  public static final double ORIGIN_LONGITUDE = -30.0;

  private GtfsGeo() {}

  /**
   * 方块 z 坐标对应的纬度。
   *
   * @param z 世界 z（米，向南为正）
   */
  public static double latitude(double z) {
    return normalize(ORIGIN_LATITUDE - z / EARTH_RADIUS_METERS * RADIANS_TO_DEGREES);
  }

  /**
   * 方块 x 坐标对应的经度。
   *
   * @param x 世界 x（米，向东为正）
   */
  public static double longitude(double x) {
    return normalize(ORIGIN_LONGITUDE + x / EARTH_RADIUS_METERS * RADIANS_TO_DEGREES);
  }

  /**
   * CSV 里的经纬度：保留 7 位小数，{@code -0.0} 写成 {@code 0.0000000}。
   *
   * @param degrees 经纬度
   */
  public static String format(double degrees) {
    double value = normalize(degrees);
    return String.format(Locale.ROOT, "%.7f", value);
  }

  /** 把 {@code -0.0} 与绝对值小于 1e-9 的坐标归一成 {@code 0.0}，避免出现负零。 */
  private static double normalize(double value) {
    double finite = Double.isFinite(value) ? value : 0.0;
    return Math.abs(finite) < 1e-9 ? 0.0 : finite;
  }
}
