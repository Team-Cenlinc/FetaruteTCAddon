package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.plan;

import java.util.Objects;

/**
 * 定点班次：指定 route 在指定时刻发车。
 *
 * <p>定点是硬约束：编表时别的车让它。{@code extra=false} 时替换同方向最近的周期格位；{@code extra=true}（书里写 {@code 加开}）在周期之外另加一班。
 *
 * @param secondOfDay 发车时刻，相对当天零点的秒数；可超过 86400 表示跨零点（{@code 24:30} 即 88200）
 * @param routeCode route 的 code
 * @param extra 是否加开
 */
public record Pin(int secondOfDay, String routeCode, boolean extra) {

  public Pin {
    Objects.requireNonNull(routeCode, "routeCode");
    routeCode = routeCode.trim();
    if (routeCode.isEmpty()) {
      throw new IllegalArgumentException("routeCode 不能为空");
    }
    if (secondOfDay < 0) {
      throw new IllegalArgumentException("时刻不能为负: " + secondOfDay);
    }
  }
}
