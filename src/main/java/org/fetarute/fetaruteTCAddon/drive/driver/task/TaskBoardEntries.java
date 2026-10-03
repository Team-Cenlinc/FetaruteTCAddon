package org.fetarute.fetaruteTCAddon.drive.driver.task;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** 任务板列哪些车次：去掉已取消、在本站终到、已被别人领走、早已开走的，正在本站停站的排最前，其余按计划发车。本类不依赖服务器对象。 */
public final class TaskBoardEntries {

  /** 计划发车已过这么久仍不在本站停站的不再列出。 */
  static final Duration DEPARTED_GRACE = Duration.ofMinutes(1);

  /**
   * 本站的一条发车。
   *
   * @param key 车次
   * @param routeId 交路
   * @param routeCode 交路代码
   * @param stopSequence 本站在交路里的停靠序号
   * @param nodeId 本站站台节点；没有时为 {@code null}
   * @param plannedDeparture 计划发车
   * @param terminating 在本站终到
   * @param cancelled 已取消
   * @param trainName 已绑定这个车次的列车；没有时为 {@code null}
   * @param dwelling 绑定的列车正停在本站
   */
  public record Row(
      TaskKey key,
      UUID routeId,
      String routeCode,
      int stopSequence,
      String nodeId,
      Instant plannedDeparture,
      boolean terminating,
      boolean cancelled,
      String trainName,
      boolean dwelling) {
    public Row {
      Objects.requireNonNull(key, "key");
      Objects.requireNonNull(plannedDeparture, "plannedDeparture");
      routeCode = routeCode == null ? "" : routeCode;
    }
  }

  private TaskBoardEntries() {}

  /**
   * 选出可领的车次。
   *
   * @param taken 已被领走的车次
   * @param limit 最多列多少条
   */
  public static List<Row> select(List<Row> rows, Set<TaskKey> taken, Instant now, int limit) {
    Map<TaskKey, Row> unique = new LinkedHashMap<>();
    for (Row row : rows) {
      if (row.cancelled() || row.terminating() || taken.contains(row.key())) {
        continue;
      }
      if (!row.dwelling() && row.plannedDeparture().isBefore(now.minus(DEPARTED_GRACE))) {
        continue;
      }
      unique.merge(
          row.key(), row, (a, b) -> a.plannedDeparture().isAfter(b.plannedDeparture()) ? b : a);
    }
    List<Row> result = new ArrayList<>(unique.values());
    result.sort(
        Comparator.comparing((Row row) -> !row.dwelling())
            .thenComparing(Row::plannedDeparture)
            .thenComparing(row -> row.key().tripCode()));
    return result.size() > limit ? List.copyOf(result.subList(0, limit)) : List.copyOf(result);
  }
}
