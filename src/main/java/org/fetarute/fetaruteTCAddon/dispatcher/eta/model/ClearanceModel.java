package org.fetarute.fetaruteTCAddon.dispatcher.eta.model;

import java.util.ArrayList;
import java.util.List;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.EtaReason;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyDecision;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResource;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.ResourceKind;

/**
 * Clearance 模型：从占用判定/阻塞信息中提取“硬停原因”。
 *
 * <p>当前仅做最小版本：
 *
 * <ul>
 *   <li>若 {@link OccupancyDecision#allowed()} 为 false，则视为 hardStop。
 *   <li>基于 blocker resource key 前缀分类输出原因（THROAT/SINGLELINE/PLATFORM）。
 * </ul>
 *
 * <p>该组件的价值在于：
 *
 * <ul>
 *   <li>Arriving 语义的否决（hardStop 时不算 arriving）
 *   <li>HUD/诊断输出 reasons
 * </ul>
 */
public final class ClearanceModel {

  public record Clearance(boolean hardStop, List<EtaReason> reasons) {
    public Clearance {
      reasons = reasons == null ? List.of() : List.copyOf(reasons);
    }
  }

  public Clearance classify(OccupancyDecision decision) {
    if (decision == null) {
      return new Clearance(false, List.of());
    }
    if (decision.allowed()) {
      return new Clearance(false, List.of());
    }

    List<EtaReason> reasons = new ArrayList<>();
    for (var claim : decision.blockers()) {
      if (claim == null || claim.resource() == null) {
        continue;
      }
      OccupancyResource res = claim.resource();
      if (res.kind() == ResourceKind.NODE) {
        reasons.add(EtaReason.PLATFORM);
        continue;
      }
      if (res.kind() == ResourceKind.CONFLICT) {
        String key = res.key();
        if (key.startsWith("switcher:")) {
          reasons.add(EtaReason.THROAT);
        } else if (key.startsWith("single:")) {
          reasons.add(EtaReason.SINGLELINE);
        }
      }
    }
    if (reasons.isEmpty()) {
      reasons.add(EtaReason.WAIT);
    }
    return new Clearance(true, reasons);
  }
}
