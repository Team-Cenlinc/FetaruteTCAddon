package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking.RailFootprintCell;

/**
 * 一次实时车体轨道足迹观测。
 *
 * <p>缺失证据与“车体已清空联锁区”语义完全不同；因此结果同时携带结构化失败原因，供启动恢复与运行时释放路径在保持 fail-closed 的同时输出可操作诊断。
 */
public record LiveRailFootprintObservation(
    Optional<Set<RailFootprintCell>> cells, FailureReason failureReason, String detail) {

  /** 现场足迹读取失败的稳定分类；{@link #NONE} 仅用于成功结果。 */
  public enum FailureReason {
    NONE,
    LEGACY_UNAVAILABLE,
    INVALID_GROUP,
    GROUP_RAIL_TRACKER_MISSING,
    GROUP_RAIL_INFORMATION_EMPTY,
    TRACKED_RAIL_INVALID,
    RAIL_BLOCK_MISSING,
    WORLD_MISMATCH,
    RAIL_PATH_EMPTY,
    MEMBER_NOT_OBSERVED,
    GROUP_EMPTY,
    CART_ENTITY_MISSING,
    CART_WORLD_MISMATCH,
    WHEEL_TRACKER_MISSING,
    CART_PROPERTIES_MISSING,
    CART_MODEL_MISSING,
    WHEEL_POSITION_MISSING,
    CART_GEOMETRY_INVALID,
    BODY_WALK_DISTANCE_EXCEEDED,
    SHARED_PATH_CONTEXT_MISSING,
    SHARED_PATH_SLICE_UNRESOLVED,
    TRACKED_PATH_RASTERIZATION_EMPTY,
    CENTER_BODY_WALK_UNRESOLVED,
    FOOTPRINT_EMPTY
  }

  public LiveRailFootprintObservation {
    cells = cells == null ? Optional.empty() : cells.map(Set::copyOf);
    failureReason = failureReason == null ? FailureReason.LEGACY_UNAVAILABLE : failureReason;
    detail = detail == null || detail.isBlank() ? "-" : detail.trim();
    if (cells.isPresent() && cells.orElseThrow().isEmpty()) {
      throw new IllegalArgumentException("实时轨道足迹成功结果不能为空集合");
    }
    if (cells.isPresent() != (failureReason == FailureReason.NONE)) {
      throw new IllegalArgumentException("足迹结果与失败原因不一致");
    }
  }

  /** 创建成功且不可变的现场足迹结果。 */
  public static LiveRailFootprintObservation available(Set<RailFootprintCell> cells) {
    return new LiveRailFootprintObservation(
        Optional.of(Objects.requireNonNull(cells, "cells")), FailureReason.NONE, "-");
  }

  /** 创建不携带可释放证据的失败结果。 */
  public static LiveRailFootprintObservation unavailable(FailureReason reason, String detail) {
    if (reason == null || reason == FailureReason.NONE) {
      throw new IllegalArgumentException("失败结果必须提供非 NONE 原因");
    }
    return new LiveRailFootprintObservation(Optional.empty(), reason, detail);
  }

  /** 是否取得了可以参与 sparse Zone 定位的完整足迹。 */
  public boolean available() {
    return cells.isPresent();
  }
}
