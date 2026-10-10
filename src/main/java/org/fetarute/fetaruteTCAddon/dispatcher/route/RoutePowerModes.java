package org.fetarute.fetaruteTCAddon.dispatcher.route;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.company.model.RouteStopPassType;
import org.fetarute.fetaruteTCAddon.drive.setup.PowerSupply;

/**
 * 交路备注 {@code POWER <受电方式>}：多模车从这个停靠点（含路径点）起换成这种受电方式。
 *
 * <p>电对电（受电弓高度、受电弓与第三轨）经过即换；涉及内燃（启停发动机）只在停车时换：写在不停车的路径点上的推迟到其后第一个停车点。 涉及内燃的那一站停站多加 {@link
 * #DIESEL_SWITCH_SECONDS}，编表、到站预测与现场停站同一口径。没写 POWER 的交路、单模车都不受影响。
 */
public final class RoutePowerModes {

  /** 涉及内燃的换模在那一站多停的秒数（启动发动机或降弓熄火）。 */
  public static final int DIESEL_SWITCH_SECONDS = 25;

  private RoutePowerModes() {}

  /** 最近一次推算过的停靠表（按列表本身认）：编表与到站预测逐站查同一份停靠表，免得每一站都从头扫一遍备注。 */
  private static volatile DwellPlan lastPlan;

  /** 一份停靠表各站的换模加时。 */
  private static final class DwellPlan {
    private final List<RouteStop> stops;
    private final int[] seconds;

    private DwellPlan(List<RouteStop> stops, int[] seconds) {
      this.stops = stops;
      this.seconds = seconds;
    }
  }

  /** 这个停靠点写的 POWER 原文；没写时为空。 */
  public static Optional<String> raw(RouteStop stop) {
    return mentionsPower(stop) ? RouteStopDirectives.target(stop, "POWER") : Optional.empty();
  }

  /** 备注里有没有 POWER 字样：先粗查再按指令解析，绝大多数停靠点不用走正则。 */
  private static boolean mentionsPower(RouteStop stop) {
    return stop != null
        && stop.notes()
            .filter(notes -> notes.toUpperCase(Locale.ROOT).contains("POWER"))
            .isPresent();
  }

  /** 这个停靠点写的 POWER；没写或认不出时为空。 */
  public static Optional<PowerSupply> directive(RouteStop stop) {
    return raw(stop).flatMap(PowerSupply::parse);
  }

  /** 换模是否涉及内燃：只能停车时换。 */
  public static boolean needsStop(PowerSupply from, PowerSupply to) {
    return from == PowerSupply.DIESEL || to == PowerSupply.DIESEL;
  }

  /** 列车在这个停靠点是否停车：PASS 不停。 */
  public static boolean dwells(RouteStop stop) {
    return stop != null && stop.passType() != RouteStopPassType.PASS;
  }

  /**
   * 交路第 {@code index} 个停靠点处应当用的受电方式：此前（含此点）最后一条认得出的 POWER。
   *
   * @return 受电方式；交路到这里还没写过 POWER 时为空（沿用列车当前的）
   */
  public static Optional<PowerSupply> modeAt(List<RouteStop> stops, int index) {
    if (stops == null) {
      return Optional.empty();
    }
    for (int i = Math.min(index, stops.size() - 1); i >= 0; i--) {
      Optional<PowerSupply> mode = directive(stops.get(i));
      if (mode.isPresent()) {
        return mode;
      }
    }
    return Optional.empty();
  }

  /**
   * 第 {@code index} 个停靠点停站要多加的换模秒数：按交路推算，车在这一站做涉及内燃的换模（含路径点上推迟到这一站的）时为 {@link
   * #DIESEL_SWITCH_SECONDS}，否则 0。
   *
   * <p>交路首站不加（出车、接续时在首站就换好，不占途中停站）；交路开头还不知道列车用什么时，换成内燃按要换算，换成电力按不用换算。 同一份停靠表连续查询时只推算一次（停靠表应当不可变）。
   */
  public static int switchDwellSeconds(List<RouteStop> stops, int index) {
    if (stops == null || index <= 0 || index >= stops.size()) {
      return 0;
    }
    DwellPlan cached = lastPlan;
    if (cached == null || cached.stops != stops) {
      cached = new DwellPlan(stops, switchDwellPlan(stops));
      lastPlan = cached;
    }
    return cached.seconds[index];
  }

  /** 整份停靠表各站的换模加时（见 {@link #switchDwellSeconds}），一遍推算完。 */
  public static int[] switchDwellPlan(List<RouteStop> stops) {
    int[] out = new int[stops == null ? 0 : stops.size()];
    PowerSupply current = null;
    PowerSupply pending = null;
    for (int i = 0; i < out.length; i++) {
      RouteStop stop = stops.get(i);
      Optional<PowerSupply> target = directive(stop);
      if (target.isPresent()) {
        pending = target.get();
      }
      if (pending == null || pending == current) {
        pending = null;
        continue;
      }
      if (!needsStop(current, pending)) {
        current = pending;
        pending = null;
        continue;
      }
      if (!dwells(stop)) {
        continue;
      }
      if (i > 0) {
        out[i] = DIESEL_SWITCH_SECONDS;
      }
      current = pending;
      pending = null;
    }
    return out;
  }
}
