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
 * <p>编表侧不设固定的折返常数，因为运行时侧<b>不存在</b>这样的量： 运行时车终到后，AutoStation 居中刹停、开门、按终到 dwell 计时，计时结束才进入待命（{@code
 * LayoverRegistry} 对 readyAt 的定义是"关门完成时间"），之后下一班的票一到就复用，没有任何最短折返。
 *
 * <p>凭空设定的常数会成为编表侧独有的第三个事实源，系统性地虚增端点占用，单股道端点尤甚。 本类取的是<b>route 定义里本来就写着的数</b>：终到停靠点的
 * dwell，车站终到再加停站开销（居中刹停 + 开门延迟）。秒数由 {@link TimetableTimingCalculator#terminalStopSeconds}
 * 算出，与途中停站共用同一套规则，不另开一个读者。
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
   * 按各 route 已算好的终到停站秒数建表。
   *
   * @param secondsByRoute 各 route 的折返秒数（见 {@link TimetableTimingCalculator#terminalStopSeconds}）
   * @param fallbackSeconds 表里没有的 route 用的值（{@code --dwell}）
   */
  public static TurnaroundTable ofSeconds(Map<UUID, Integer> secondsByRoute, int fallbackSeconds) {
    Map<UUID, Integer> byRoute = new LinkedHashMap<>();
    if (secondsByRoute != null) {
      secondsByRoute.forEach(
          (routeId, seconds) -> {
            if (routeId != null && seconds != null) {
              byRoute.put(routeId, Math.max(0, seconds));
            }
          });
    }
    return new TurnaroundTable(byRoute, Math.max(0, fallbackSeconds), false);
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
   * 报告文案里的来源说明：显式覆盖，还是按各 route 终到站停站（dwell，车站另加停站开销）。
   *
   * <p>表还没建起来（命令层拿到的那份）时只说来源不说数字——具体秒数由调用方从它手上的数据打印， 两处不各报一个可能对不上的值。
   */
  public String describe() {
    if (fixed) {
      return "--turnaround " + fallbackSeconds + "s";
    }
    if (byRoute.isEmpty()) {
      return "终到站停站";
    }
    int min = minimumSeconds();
    int max = maximumSeconds();
    return min == max ? "终到站停站 " + min + "s" : "终到站停站 " + min + "–" + max + "s";
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
