package org.fetarute.fetaruteTCAddon.display.pids;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 一个车站在某一时刻的站台屏数据。
 *
 * <p>同一车站的所有屏幕共用一份，各屏按站台、线路自行过滤。只存绝对时刻，倒计时由渲染侧按当前时间计算，快照不必每秒重取。
 *
 * @param station 车站
 * @param takenAt 取数时刻
 * @param rows 各行，按 {@link PidsRow#expectedAt()} 升序
 */
public record PidsSnapshot(PidsStationKey station, Instant takenAt, List<PidsRow> rows) {

  public PidsSnapshot {
    Objects.requireNonNull(station, "station");
    Objects.requireNonNull(takenAt, "takenAt");
    rows = rows == null ? List.of() : List.copyOf(rows);
  }
}
