package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.plan;

import java.util.List;

/**
 * 服务模式：运行计划书解析出的分时段频率与定点班次。{@link #NONE} 表示没有计划书。
 *
 * <p>两张表都按书里的书写顺序排列；同一组键的时段互不重叠由 {@link TimetablePlanBook} 保证。
 *
 * @param bands 分时段频率
 * @param pins 定点班次
 */
public record ServicePattern(List<Band> bands, List<Pin> pins) {

  /** 没有计划书。 */
  public static final ServicePattern NONE = new ServicePattern(List.of(), List.of());

  public ServicePattern {
    bands = bands == null ? List.of() : List.copyOf(bands);
    pins = pins == null ? List.of() : List.copyOf(pins);
  }

  /** 既没有时段也没有定点。 */
  public boolean isEmpty() {
    return bands.isEmpty() && pins.isEmpty();
  }
}
