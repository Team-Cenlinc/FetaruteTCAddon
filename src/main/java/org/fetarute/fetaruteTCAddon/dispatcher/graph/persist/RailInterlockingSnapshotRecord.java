package org.fetarute.fetaruteTCAddon.dispatcher.graph.persist;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking.InterlockingZoneInfo;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking.RailInterlockingCoverage;

/**
 * 一个世界的稀疏物理联锁持久化快照。
 *
 * <p>快照只保存 coverage、图 Edge 签名和真实 Zone 的局部检测几何，不包含普通区间的轨道方块。旧库缺少该记录或记录损坏时，加载器必须创建 incomplete
 * sentinel，而不能把缺失解释为“没有物理冲突”。
 *
 * @param worldId 所属世界
 * @param formatVersion 稀疏快照格式版本
 * @param edgeSignature 规范化 Edge universe 的稳定摘要
 * @param coverage 本次自动发现覆盖状态
 * @param zones 稳定 Zone key 到局部物理证据的映射
 */
public record RailInterlockingSnapshotRecord(
    UUID worldId,
    int formatVersion,
    String edgeSignature,
    RailInterlockingCoverage coverage,
    Map<String, InterlockingZoneInfo> zones) {

  /** 当前运行时能够解释的稀疏快照格式。 */
  public static final int CURRENT_FORMAT_VERSION = 1;

  public RailInterlockingSnapshotRecord {
    Objects.requireNonNull(worldId, "worldId");
    Objects.requireNonNull(edgeSignature, "edgeSignature");
    Objects.requireNonNull(coverage, "coverage");
    Objects.requireNonNull(zones, "zones");
    if (formatVersion < 0) {
      throw new IllegalArgumentException("formatVersion 不能为负");
    }
    if (edgeSignature.isBlank()) {
      throw new IllegalArgumentException("edgeSignature 不能为空");
    }
    if (coverage.inputEdgeCount() < 0
        || coverage.participatingEdgeCount() < 0
        || coverage.participatingEdgeCount() > coverage.inputEdgeCount()
        || (coverage.complete()
            && coverage.participatingEdgeCount() != coverage.inputEdgeCount())) {
      throw new IllegalArgumentException("联锁 coverage 计数不合法");
    }
    Map<String, InterlockingZoneInfo> sorted = new TreeMap<>();
    zones.forEach(
        (key, zone) -> {
          if (key == null || key.isBlank() || zone == null || !key.equals(zone.zoneKey())) {
            throw new IllegalArgumentException("Zone key 与记录不一致");
          }
          sorted.put(key, zone);
        });
    zones = Collections.unmodifiableMap(sorted);
  }
}
