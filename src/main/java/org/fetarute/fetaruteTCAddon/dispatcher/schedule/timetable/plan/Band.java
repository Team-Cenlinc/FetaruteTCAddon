package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.plan;

import java.util.Objects;
import java.util.Optional;

/**
 * 分时段频率：某交路组在 {@code [fromSecond, toSecond)} 内的发车间隔。
 *
 * <p>组键与 {@code --group-headway} 同语义：{@code line} 为空时凡有这个组名的线都适用（书里写 {@code 组}）；有值时只作用于那条线（书里写
 * {@code 线/组}）。
 *
 * @param line 限定的线路 code；为空表示不限线路
 * @param group 交路组名（route metadata 的 {@code spawn_group}，未分组的 route 属于 {@code default}）
 * @param fromSecond 起点，相对当天零点的秒数（含）
 * @param toSecond 终点，相对当天零点的秒数（不含），可超过一天表示跨零点
 * @param intervalSeconds 发车间隔，秒
 */
public record Band(
    Optional<String> line, String group, int fromSecond, int toSecond, int intervalSeconds) {

  public Band {
    Objects.requireNonNull(line, "line");
    Objects.requireNonNull(group, "group");
    line = line.map(String::trim).filter(code -> !code.isEmpty());
    group = group.trim();
    if (group.isEmpty()) {
      throw new IllegalArgumentException("group 不能为空");
    }
    if (fromSecond < 0 || toSecond <= fromSecond) {
      throw new IllegalArgumentException("时段须满足 0 <= from < to: " + fromSecond + "-" + toSecond);
    }
    if (intervalSeconds <= 0) {
      throw new IllegalArgumentException("间隔须为正: " + intervalSeconds);
    }
  }

  /** 书里的组键写法：限定线路时为 {@code 线/组}，否则为 {@code 组}。 */
  public String key() {
    return line.map(code -> code + "/" + group).orElse(group);
  }

  /** 时刻落在本时段内（左闭右开）。 */
  public boolean contains(int secondOfDay) {
    return secondOfDay >= fromSecond && secondOfDay < toSecond;
  }
}
