package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 折返时间表：车按某条 route 到达终点后，多久能再发车。
 *
 * <h2>为什么不是一个常数</h2>
 *
 * <p>这里曾经是 {@code Limits.turnaroundSeconds}，一个默认 180 秒的标量。那个数在运行时侧<b>并不存在</b>： {@code
 * RuntimeDispatchService} 注册待命车时写的是 {@code readyAt = 当前时间 + 终到站 dwell}（dwell 为 0 时立即就绪）， {@code
 * LayoverRegistry} 对 readyAt 的定义是"关门完成时间"。也就是说运行时没有任何最短折返， 车终到即待命，下一班的票一到就复用。
 *
 * <p>一个凭空造出来的 180 秒因此是编表侧独有的第三个事实源，而它正在系统性地虚增端点占用——实测中它一项就贡献了 单股道端点九成的占用。本类把这个量换回<b>route
 * 定义里本来就写着的数</b>：终到停靠点的 dwell， 解析走 {@link
 * TimetableTimingCalculator#terminalDwellSeconds}，与行程时分共用同一套规则，不另开一个读者。
 *
 * <h2>按 route 而不是按节点</h2>
 *
 * <p>同一个站台可以被两条 route 以不同的 dwell 终到（快车停 20 秒、慢车停 30 秒）。按节点建表会把两者压成一个数， 于是快车被慢车拖慢。按 routeId
 * 建表是精确的，而且每一个使用点手上都有 routeId。
 *
 * <p>"需要等更久"这件事不由本表表达：端点串行（{@link TerminalSerializer}）与让车修复（{@link ResourceRepair}）
 * 会在资源要求的时候把发车往后推。本表只回答"物理上最早什么时候能走"。
 *
 * @param byRoute 按 route 的折返秒数
 * @param fallbackSeconds 表里没有这条 route 时用的值
 * @param fixed 是否来自 {@code --turnaround} 的显式全线覆盖（只影响报告文案）
 */
public record TurnaroundTable(Map<UUID, Integer> byRoute, int fallbackSeconds, boolean fixed) {

  public TurnaroundTable {
    byRoute = byRoute == null ? Map.of() : Map.copyOf(byRoute);
    fallbackSeconds = Math.max(0, fallbackSeconds);
  }

  /** 空表：所有 route 都是"到了就能走"。只用于没有 route 上下文的兜底场景。 */
  public static TurnaroundTable none() {
    return new TurnaroundTable(Map.of(), 0, false);
  }

  /**
   * {@code --turnaround} 的显式全线覆盖：所有 route 用同一个数。
   *
   * <p>运营方明确要求"每个端点至少留这么久"时用它。不传 {@code --turnaround} 时系统里<b>不存在</b>这样一个全线数。
   *
   * @param seconds 折返秒数，负值夹到 0
   */
  public static TurnaroundTable fixed(int seconds) {
    return new TurnaroundTable(Map.of(), seconds, true);
  }

  /**
   * 从 route 的停靠配置建表。
   *
   * @param stopsByRoute 各 route 的停靠配置（按 sequence 升序）
   * @param dwellFallbackSeconds 停靠却没配 dwell 时的兜底值（{@code --dwell}）
   */
  public static TurnaroundTable ofStops(
      Map<UUID, ? extends java.util.List<org.fetarute.fetaruteTCAddon.company.model.RouteStop>>
          stopsByRoute,
      int dwellFallbackSeconds) {
    Map<UUID, Integer> byRoute = new LinkedHashMap<>();
    if (stopsByRoute != null) {
      stopsByRoute.forEach(
          (routeId, stops) -> {
            if (routeId != null) {
              byRoute.put(
                  routeId,
                  TimetableTimingCalculator.terminalDwellSeconds(stops, dwellFallbackSeconds));
            }
          });
    }
    return new TurnaroundTable(byRoute, Math.max(0, dwellFallbackSeconds), false);
  }

  /**
   * 这条 route 的折返时间。
   *
   * @param routeId route UUID，{@code null} 时返回兜底值
   */
  public int secondsFor(UUID routeId) {
    if (fixed || routeId == null) {
      return fallbackSeconds;
    }
    Integer value = byRoute.get(routeId);
    return value == null ? fallbackSeconds : Math.max(0, value);
  }

  /**
   * 全表最小值，给"最短的那一班"这类下界估算用。
   *
   * <p>下界必须是所有 route 里最短的那个：用大于它的数会把本来接得上的班次判成接不上， 那正是"宁可少排一班"这条规则被误触发的方式。
   */
  public int minimumSeconds() {
    if (fixed || byRoute.isEmpty()) {
      return fallbackSeconds;
    }
    int min = Integer.MAX_VALUE;
    for (Integer value : byRoute.values()) {
      min = Math.min(min, value == null ? 0 : Math.max(0, value));
    }
    return min == Integer.MAX_VALUE ? fallbackSeconds : min;
  }

  /** 全表最大值，报告里说明折返时间的取值范围时用。 */
  public int maximumSeconds() {
    if (fixed || byRoute.isEmpty()) {
      return fallbackSeconds;
    }
    int max = 0;
    for (Integer value : byRoute.values()) {
      max = Math.max(max, value == null ? 0 : value);
    }
    return max;
  }

  /**
   * 报告文案里的来源说明：显式覆盖，还是按各 route 终到站 dwell。
   *
   * <p>表还没建起来（命令层拿到的那份）时只说来源不说数字——具体秒数由调用方从它手上的数据打印， 两处不各报一个可能对不上的值。
   */
  public String describe() {
    if (fixed) {
      return "--turnaround " + fallbackSeconds + "s";
    }
    if (byRoute.isEmpty()) {
      return "终到站 dwell";
    }
    int min = minimumSeconds();
    int max = maximumSeconds();
    return min == max ? "终到站 dwell " + min + "s" : "终到站 dwell " + min + "–" + max + "s";
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    }
    if (!(obj instanceof TurnaroundTable other)) {
      return false;
    }
    return fixed == other.fixed
        && fallbackSeconds == other.fallbackSeconds
        && byRoute.equals(other.byRoute);
  }

  @Override
  public int hashCode() {
    return Objects.hash(byRoute, fallbackSeconds, fixed);
  }
}
