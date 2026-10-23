package org.fetarute.fetaruteTCAddon.dispatcher.route;

import java.util.List;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;

/**
 * 交路各停靠点在 dwell 之外多停的秒数：多模车换模（{@link RoutePowerModes#switchDwellPlan}）加上加解编（{@link
 * RouteCouplingDirectives#extraDwellPlan}）。编表、到站预测与现场停站都读这一份，三处同一口径。纯计算。
 */
public final class RouteStopExtraDwell {

  private RouteStopExtraDwell() {}

  /** 各停靠点多停的秒数，与停靠表一一对应。 */
  public static int[] plan(List<RouteStop> stops) {
    int[] power = RoutePowerModes.switchDwellPlan(stops);
    int[] coupling = RouteCouplingDirectives.extraDwellPlan(stops);
    int[] out = new int[Math.max(power.length, coupling.length)];
    for (int i = 0; i < out.length; i++) {
      out[i] = (i < power.length ? power[i] : 0) + (i < coupling.length ? coupling[i] : 0);
    }
    return out;
  }
}
