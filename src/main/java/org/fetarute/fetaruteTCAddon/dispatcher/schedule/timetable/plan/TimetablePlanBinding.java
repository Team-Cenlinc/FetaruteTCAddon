package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.plan;

import java.util.Objects;
import java.util.UUID;

/**
 * 时刻表编号与运行计划书的绑定：运营商下凡用这个编号编的表（含几条线联编）都按这本计划书出车。
 *
 * <p>编号不必已经存在：可以先绑定再编表。每个编号最多绑定一本计划书。
 *
 * @param operatorId 运营商
 * @param timetableCode 时刻表编号（与 {@code /fta timetable build} 的 code 一致，区分大小写）
 * @param planId 计划书主键，须属于同一运营商
 */
public record TimetablePlanBinding(UUID operatorId, String timetableCode, UUID planId) {

  public TimetablePlanBinding {
    Objects.requireNonNull(operatorId, "operatorId");
    Objects.requireNonNull(timetableCode, "timetableCode");
    Objects.requireNonNull(planId, "planId");
    timetableCode = timetableCode.trim();
    if (timetableCode.isEmpty()) {
      throw new IllegalArgumentException("timetableCode 不能为空");
    }
  }
}
