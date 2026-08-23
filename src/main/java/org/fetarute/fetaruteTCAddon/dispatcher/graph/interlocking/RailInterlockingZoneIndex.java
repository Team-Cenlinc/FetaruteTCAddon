package org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;

/**
 * 从区间物理足迹推导跨区间联锁资源的不可变索引。
 *
 * <p>索引只比较格式版本受支持且探索完整的足迹。共享端点的相邻区间若只在一个连续方块簇内重合，视为正常拓扑连接；若两者在远处再次形成不连通重合簇，仍会生成
 * pair-zone，避免环回、折返或复杂 TCC 曲线因“共享端点”被整体漏检。其它区间仅在实际共享同一三维方块坐标时生成独立联锁区。
 */
public final class RailInterlockingZoneIndex {

  private static final String KEY_PREFIX = "interlocking:";

  private final Map<EdgeId, Set<String>> zoneKeysByEdge;
  private final Map<String, InterlockingZoneInfo> zoneInfoByKey;
  private final Map<RailFootprintCell, Set<String>> zoneKeysByCell;
  private final RailInterlockingCoverage coverage;

  private RailInterlockingZoneIndex(
      Map<EdgeId, Set<String>> zoneKeysByEdge,
      Map<String, InterlockingZoneInfo> zoneInfoByKey,
      RailInterlockingCoverage coverage) {
    Map<EdgeId, Set<String>> immutableByEdge = new HashMap<>();
    zoneKeysByEdge.forEach(
        (edge, keys) ->
            immutableByEdge.put(edge, Collections.unmodifiableSet(new TreeSet<>(keys))));
    this.zoneKeysByEdge = Map.copyOf(immutableByEdge);
    this.zoneInfoByKey = Collections.unmodifiableMap(new TreeMap<>(zoneInfoByKey));
    this.zoneKeysByCell = buildZoneCellIndex(zoneInfoByKey);
    this.coverage = coverage;
  }

  /**
   * 根据一个世界内的区间足迹构建索引。
   *
   * @param worldId 足迹所属世界，用于隔离不同世界中坐标与区间相同的联锁键
   * @param footprintsByEdge 区间到物理足迹的映射
   * @return 完全脱离输入集合、可安全共享的不可变索引
   */
  public static RailInterlockingZoneIndex from(
      UUID worldId, Map<EdgeId, RailEdgeFootprint> footprintsByEdge) {
    Objects.requireNonNull(footprintsByEdge, "footprintsByEdge");
    return from(worldId, footprintsByEdge.keySet(), footprintsByEdge);
  }

  /**
   * 根据图快照的完整 edge universe 构建索引。
   *
   * <p>coverage 必须相对图中预期存在的全部区间计算，而不是相对“恰好成功写入 footprint map”的子集计算。缺失、未来格式、旧格式、不完整或空足迹都会使结果保持
   * fail-closed incomplete。
   *
   * @param worldId 足迹所属世界
   * @param expectedEdges 图快照中预期存在的全部区间
   * @param footprintsByEdge 已捕获的区间足迹
   * @return 基于完整 edge universe 的联锁索引
   */
  public static RailInterlockingZoneIndex from(
      UUID worldId, Set<EdgeId> expectedEdges, Map<EdgeId, RailEdgeFootprint> footprintsByEdge) {
    Objects.requireNonNull(worldId, "worldId");
    Objects.requireNonNull(expectedEdges, "expectedEdges");
    Objects.requireNonNull(footprintsByEdge, "footprintsByEdge");

    Set<EdgeId> expected = new HashSet<>();
    for (EdgeId edge : expectedEdges) {
      if (edge != null) {
        expected.add(InterlockingZoneInfo.canonical(edge));
      }
    }
    Map<EdgeId, RailEdgeFootprint> canonicalFootprints = new HashMap<>();
    for (Map.Entry<EdgeId, RailEdgeFootprint> entry : footprintsByEdge.entrySet()) {
      if (entry.getKey() != null) {
        canonicalFootprints.put(InterlockingZoneInfo.canonical(entry.getKey()), entry.getValue());
      }
    }
    Map<RailFootprintCell, EdgeId> soleEdgeByCell = new HashMap<>();
    Map<RailFootprintCell, Set<EdgeId>> sharedEdgesByCell = new HashMap<>();
    int participatingEdgeCount = 0;
    boolean complete = true;
    for (EdgeId edge : expected) {
      RailEdgeFootprint footprint = canonicalFootprints.get(edge);
      if (footprint == null || !footprint.participatesInInterlocking()) {
        complete = false;
        continue;
      }
      participatingEdgeCount++;
      for (RailFootprintCell cell : footprint.cells()) {
        addCellEdge(soleEdgeByCell, sharedEdgesByCell, cell, edge);
      }
    }

    Map<EdgePair, Set<RailFootprintCell>> overlapByPair = new HashMap<>();
    for (Map.Entry<RailFootprintCell, Set<EdgeId>> entry : sharedEdgesByCell.entrySet()) {
      List<EdgeId> edges = new ArrayList<>(entry.getValue());
      edges.sort(InterlockingZoneInfo::compareEdges);
      for (int firstIndex = 0; firstIndex < edges.size(); firstIndex++) {
        for (int secondIndex = firstIndex + 1; secondIndex < edges.size(); secondIndex++) {
          EdgeId first = edges.get(firstIndex);
          EdgeId second = edges.get(secondIndex);
          overlapByPair
              .computeIfAbsent(new EdgePair(first, second), ignored -> new TreeSet<>())
              .add(entry.getKey());
        }
      }
    }

    Map<EdgeId, Set<String>> zoneKeysByEdge = new HashMap<>();
    Map<String, InterlockingZoneInfo> infoByKey = new TreeMap<>();
    overlapByPair.forEach(
        (pair, cells) -> {
          if (sharesEndpoint(pair.first(), pair.second())
              && !hasDisconnectedOverlapComponents(cells)) {
            return;
          }
          String key = buildKey(worldId, pair.first(), pair.second());
          InterlockingZoneInfo info =
              new InterlockingZoneInfo(key, pair.first(), pair.second(), cells);
          infoByKey.put(key, info);
          zoneKeysByEdge.computeIfAbsent(pair.first(), ignored -> new TreeSet<>()).add(key);
          zoneKeysByEdge.computeIfAbsent(pair.second(), ignored -> new TreeSet<>()).add(key);
        });

    return new RailInterlockingZoneIndex(
        zoneKeysByEdge,
        infoByKey,
        new RailInterlockingCoverage(expected.size(), participatingEdgeCount, complete));
  }

  /**
   * 从已经持久化并校验过的稀疏 Zone 恢复索引。
   *
   * <p>该入口不接收任何普通轨道 cell。Zone 引用未知 Edge、key 不一致或 coverage 与 Edge universe 不一致时立即拒绝，调用方应降级为
   * incomplete sentinel。
   *
   * @param expectedEdges 当前图快照的全部区间
   * @param coverage 持久化的自动发现覆盖状态
   * @param zones 稀疏 Zone 记录
   * @return 只包含局部 Zone 几何的不可变索引
   */
  public static RailInterlockingZoneIndex fromZones(
      Set<EdgeId> expectedEdges,
      RailInterlockingCoverage coverage,
      Map<String, InterlockingZoneInfo> zones) {
    Objects.requireNonNull(expectedEdges, "expectedEdges");
    Objects.requireNonNull(coverage, "coverage");
    Objects.requireNonNull(zones, "zones");
    Set<EdgeId> expected = new HashSet<>();
    expectedEdges.stream()
        .filter(Objects::nonNull)
        .map(InterlockingZoneInfo::canonical)
        .forEach(expected::add);
    if (coverage.inputEdgeCount() != expected.size()
        || coverage.participatingEdgeCount() < 0
        || coverage.participatingEdgeCount() > expected.size()
        || (coverage.complete() && coverage.participatingEdgeCount() != expected.size())) {
      throw new IllegalArgumentException("持久化联锁 coverage 与 Edge universe 不一致");
    }
    Map<EdgeId, Set<String>> keysByEdge = new HashMap<>();
    Map<String, InterlockingZoneInfo> infoByKey = new TreeMap<>();
    zones.forEach(
        (key, zone) -> {
          if (key == null
              || zone == null
              || !key.equals(zone.zoneKey())
              || zone.overlapCells().isEmpty()
              || !expected.contains(zone.firstEdge())
              || !expected.contains(zone.secondEdge())) {
            throw new IllegalArgumentException("持久化 Zone 引用了无效 Edge 或局部几何");
          }
          if (infoByKey.putIfAbsent(key, zone) != null) {
            throw new IllegalArgumentException("持久化 Zone key 重复");
          }
          keysByEdge.computeIfAbsent(zone.firstEdge(), ignored -> new TreeSet<>()).add(key);
          keysByEdge.computeIfAbsent(zone.secondEdge(), ignored -> new TreeSet<>()).add(key);
        });
    return new RailInterlockingZoneIndex(keysByEdge, infoByKey, coverage);
  }

  /** 返回指定区间参与的全部联锁区键；未知区间返回空集合。 */
  public Set<String> zoneKeysForEdge(EdgeId edgeId) {
    if (edgeId == null) {
      return Set.of();
    }
    return zoneKeysByEdge.getOrDefault(InterlockingZoneInfo.canonical(edgeId), Set.of());
  }

  /** 按稳定资源键查询联锁区详情。 */
  public Optional<InterlockingZoneInfo> zoneInfo(String zoneKey) {
    if (zoneKey == null) {
      return Optional.empty();
    }
    return Optional.ofNullable(zoneInfoByKey.get(zoneKey));
  }

  /** 返回所有联锁区详情的不可变快照。 */
  public Map<String, InterlockingZoneInfo> zones() {
    return zoneInfoByKey;
  }

  /** 返回包含指定现场轨道方块的全部稀疏联锁区；普通轨道坐标返回空集合。 */
  public Set<String> zoneKeysForCell(RailFootprintCell cell) {
    if (cell == null) {
      return Set.of();
    }
    return zoneKeysByCell.getOrDefault(cell, Set.of());
  }

  /** 返回常驻稀疏空间索引覆盖的联锁区方块总数。 */
  public int indexedZoneCellCount() {
    return zoneKeysByCell.size();
  }

  /** 返回一个物理方块同时属于多个联锁区的数量。 */
  public int multiZoneCellCount() {
    return (int) zoneKeysByCell.values().stream().filter(keys -> keys.size() > 1).count();
  }

  /** 返回本次索引构建对输入区间足迹的覆盖状态。 */
  public RailInterlockingCoverage coverage() {
    return coverage;
  }

  /** 把同一坐标的第二条边提升为共享集合，普通坐标不分配 singleton 集合。 */
  private static void addCellEdge(
      Map<RailFootprintCell, EdgeId> soleEdgeByCell,
      Map<RailFootprintCell, Set<EdgeId>> sharedEdgesByCell,
      RailFootprintCell cell,
      EdgeId edge) {
    Set<EdgeId> sharedEdges = sharedEdgesByCell.get(cell);
    if (sharedEdges != null) {
      sharedEdges.add(edge);
      return;
    }
    EdgeId existing = soleEdgeByCell.putIfAbsent(cell, edge);
    if (existing == null || existing.equals(edge)) {
      return;
    }
    Set<EdgeId> promoted = new HashSet<>();
    promoted.add(existing);
    promoted.add(edge);
    soleEdgeByCell.remove(cell);
    sharedEdgesByCell.put(cell, promoted);
  }

  /** 只从最终 Zone 的局部检测坐标建立常驻空间索引，普通区间方块不会进入结果。 */
  private static Map<RailFootprintCell, Set<String>> buildZoneCellIndex(
      Map<String, InterlockingZoneInfo> zones) {
    Map<RailFootprintCell, Set<String>> mutable = new HashMap<>();
    zones.forEach(
        (key, zone) -> {
          for (RailFootprintCell cell : zone.overlapCells()) {
            mutable.computeIfAbsent(cell, ignored -> new TreeSet<>()).add(key);
          }
        });
    Map<RailFootprintCell, Set<String>> frozen = new HashMap<>();
    mutable.forEach((cell, keys) -> frozen.put(cell, Collections.unmodifiableSet(keys)));
    return Map.copyOf(frozen);
  }

  private static boolean sharesEndpoint(EdgeId first, EdgeId second) {
    return first.a().equals(second.a())
        || first.a().equals(second.b())
        || first.b().equals(second.a())
        || first.b().equals(second.b());
  }

  /**
   * 判断重合方块是否形成两个以上的六邻接连通簇。
   *
   * <p>共享端点的正常连接通常只产生一个局部连续簇；第二个不连通簇意味着两条区间在离开共同端点后又发生了实体重叠。这里只需判断“是否多簇”，无需保存额外图结构，构建复杂度与重合方块数线性相关。
   */
  private static boolean hasDisconnectedOverlapComponents(Set<RailFootprintCell> cells) {
    if (cells == null || cells.size() < 2) {
      return false;
    }
    Set<RailFootprintCell> remaining = new HashSet<>(cells);
    ArrayDeque<RailFootprintCell> pending = new ArrayDeque<>();
    RailFootprintCell seed = remaining.iterator().next();
    remaining.remove(seed);
    pending.add(seed);
    while (!pending.isEmpty()) {
      RailFootprintCell cell = pending.removeFirst();
      addIfRemaining(remaining, pending, new RailFootprintCell(cell.x() - 1, cell.y(), cell.z()));
      addIfRemaining(remaining, pending, new RailFootprintCell(cell.x() + 1, cell.y(), cell.z()));
      addIfRemaining(remaining, pending, new RailFootprintCell(cell.x(), cell.y() - 1, cell.z()));
      addIfRemaining(remaining, pending, new RailFootprintCell(cell.x(), cell.y() + 1, cell.z()));
      addIfRemaining(remaining, pending, new RailFootprintCell(cell.x(), cell.y(), cell.z() - 1));
      addIfRemaining(remaining, pending, new RailFootprintCell(cell.x(), cell.y(), cell.z() + 1));
    }
    return !remaining.isEmpty();
  }

  private static void addIfRemaining(
      Set<RailFootprintCell> remaining,
      ArrayDeque<RailFootprintCell> pending,
      RailFootprintCell candidate) {
    if (remaining.remove(candidate)) {
      pending.addLast(candidate);
    }
  }

  private static String buildKey(UUID worldId, EdgeId first, EdgeId second) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      digest.update(
          ByteBuffer.allocate(Long.BYTES * 2)
              .putLong(worldId.getMostSignificantBits())
              .putLong(worldId.getLeastSignificantBits())
              .array());
      updateString(digest, first.a().value());
      updateString(digest, first.b().value());
      updateString(digest, second.a().value());
      updateString(digest, second.b().value());
      return KEY_PREFIX + toHex(digest.digest());
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("当前 Java 运行时不支持 SHA-256", exception);
    }
  }

  private static void updateString(MessageDigest digest, String value) {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
    digest.update(bytes);
  }

  private static String toHex(byte[] bytes) {
    StringBuilder result = new StringBuilder(bytes.length * 2);
    for (byte value : bytes) {
      result.append(Character.forDigit((value >>> 4) & 0x0f, 16));
      result.append(Character.forDigit(value & 0x0f, 16));
    }
    return result.toString();
  }

  private record EdgePair(EdgeId first, EdgeId second) {

    private EdgePair {
      if (InterlockingZoneInfo.compareEdges(first, second) > 0) {
        EdgeId swap = first;
        first = second;
        second = swap;
      }
    }
  }
}
