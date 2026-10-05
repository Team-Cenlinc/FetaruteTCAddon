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

/**
 * 任务板列哪些车次：去掉已取消、在本站终到、早已开走的，正在本站停站的排最前，其余按计划发车。本类不依赖服务器对象。
 *
 * <p>已被领走的车次：可领车次的清单（{@link #select}）里去掉；任务板（{@link #board}）照样列出，标明被谁领走、不能再领。
 */
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
   * 已领走一个车次的玩家。
   *
   * @param playerId 玩家
   * @param playerName 玩家名
   */
  public record Claimant(UUID playerId, String playerName) {
    public Claimant {
      Objects.requireNonNull(playerId, "playerId");
      playerName = playerName == null ? "" : playerName;
    }
  }

  /**
   * 车次的行程概要（按时刻表）。
   *
   * @param destination 终点站站名
   * @param stopCount 本站之后停车的车站数，含终点站
   * @param runSeconds 按表的运行时长（秒）；不明时为 -1
   */
  public record Trip(String destination, int stopCount, long runSeconds) {
    public Trip {
      destination = destination == null ? "" : destination;
    }
  }

  /**
   * 任务板上的一格。
   *
   * @param row 本站的这条发车
   * @param trip 行程概要；查不到时为 {@code null}
   * @param claimant 已领走这一班的玩家；没人领时为 {@code null}
   */
  public record Entry(Row row, Trip trip, Claimant claimant) {
    public Entry {
      Objects.requireNonNull(row, "row");
    }

    /** 已被领走，不能再领。 */
    public boolean claimed() {
      return claimant != null;
    }

    /** 是不是这名玩家自己领的。 */
    public boolean claimedBy(UUID playerId) {
      return claimant != null && claimant.playerId().equals(playerId);
    }

    /** 补上行程概要。 */
    public Entry withTrip(Trip value) {
      return new Entry(row, value, claimant);
    }
  }

  /**
   * 选出可领的车次。
   *
   * @param taken 已被领走的车次
   * @param limit 最多列多少条
   */
  public static List<Row> select(List<Row> rows, Set<TaskKey> taken, Instant now, int limit) {
    List<Row> result = new ArrayList<>();
    for (Row row : candidates(rows, now)) {
      if (!taken.contains(row.key())) {
        result.add(row);
      }
    }
    return result.size() > limit ? List.copyOf(result.subList(0, limit)) : List.copyOf(result);
  }

  /**
   * 任务板上列的车次：与 {@link #select} 同样筛选与排序，但已被领走的不去掉，标上领取人。
   *
   * @param claimants 已被领走的车次与领取人
   * @param limit 最多列多少条
   */
  public static List<Entry> board(
      List<Row> rows, Map<TaskKey, Claimant> claimants, Instant now, int limit) {
    List<Entry> result = new ArrayList<>();
    for (Row row : candidates(rows, now)) {
      if (result.size() >= limit) {
        break;
      }
      result.add(new Entry(row, null, claimants.get(row.key())));
    }
    return List.copyOf(result);
  }

  /** 去掉取消、终到、早已开走的，同一车次只留较早的一次，停站中的排最前。 */
  private static List<Row> candidates(List<Row> rows, Instant now) {
    Map<TaskKey, Row> unique = new LinkedHashMap<>();
    for (Row row : rows) {
      if (row.cancelled() || row.terminating()) {
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
    return result;
  }
}
