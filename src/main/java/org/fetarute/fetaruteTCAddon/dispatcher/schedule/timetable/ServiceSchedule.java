package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.plan.Band;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.plan.Pin;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.plan.ServicePattern;

/**
 * 运行计划书在编表里的形状：时段已按编表的组键（联编时带线前缀）归好组，定点班次原样带着。
 *
 * <p>组键的取法与 {@code --group-headway} 一致：书里写 {@code 线/组} 的只给那条线的这个组，写 {@code 组} 的给凡有这个组名的线；
 * 同一组键两种都有时限定了线路的排在前面，重叠的时刻用它。命令行 {@code --group-headway} 显式点名的组全天用命令给的间隔，计划书不再管它。
 *
 * @param bandsByGroup 组键 → 时段，按优先级排好
 * @param pins 定点班次
 */
public record ServiceSchedule(Map<String, List<Band>> bandsByGroup, List<Pin> pins) {

  /** 没有计划书：全天一个制式，没有定点班次。 */
  public static final ServiceSchedule NONE = new ServiceSchedule(Map.of(), List.of());

  public ServiceSchedule {
    Map<String, List<Band>> copy = new LinkedHashMap<>();
    if (bandsByGroup != null) {
      bandsByGroup.forEach((group, bands) -> copy.put(group, List.copyOf(bands)));
    }
    bandsByGroup = Map.copyOf(copy);
    pins = pins == null ? List.of() : List.copyOf(pins);
  }

  /** 既没有时段也没有定点。 */
  public boolean isEmpty() {
    return bandsByGroup.isEmpty() && pins.isEmpty();
  }

  /**
   * 把计划书的组键换成编表的组键。
   *
   * @param pattern 计划书解析结果
   * @param groupsByLine 线路 code → 这条线参与编表的交路组名
   * @param prefixed 是否联编（组键带线前缀，见 {@link TimetableSetBuilder#groupKey}）
   * @param explicit 命令行显式点名的组键（编表口径）：全天用命令给的间隔
   */
  public static ServiceSchedule of(
      ServicePattern pattern,
      Map<String, List<String>> groupsByLine,
      boolean prefixed,
      Set<String> explicit) {
    if (pattern == null || pattern.isEmpty()) {
      return NONE;
    }
    Map<String, List<Band>> out = new LinkedHashMap<>();
    groupsByLine.forEach(
        (line, groups) -> {
          for (String group : groups) {
            String key = TimetableSetBuilder.groupKey(line, Optional.of(group), prefixed);
            if (explicit.contains(key)) {
              continue;
            }
            List<Band> bands = new ArrayList<>();
            for (Band band : pattern.bands()) {
              if (band.group().equals(group)
                  && band.line().filter(line::equalsIgnoreCase).isPresent()) {
                bands.add(band);
              }
            }
            for (Band band : pattern.bands()) {
              if (band.group().equals(group) && band.line().isEmpty()) {
                bands.add(band);
              }
            }
            if (!bands.isEmpty()) {
              out.put(key, bands);
            }
          }
        });
    return new ServiceSchedule(out, pattern.pins());
  }
}
