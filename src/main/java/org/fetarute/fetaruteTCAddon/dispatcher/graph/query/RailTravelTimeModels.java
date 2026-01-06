package org.fetarute.fetaruteTCAddon.dispatcher.graph.query;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/** {@link RailTravelTimeModel} 的常用实现集合。 */
public final class RailTravelTimeModels {

  private RailTravelTimeModels() {}

  /**
   * 常量速度模型：ETA = lengthBlocks / speed。
   *
   * @param speedBlocksPerSecond 速度（blocks per second），必须为正数
   */
  public static RailTravelTimeModel constantSpeed(double speedBlocksPerSecond) {
    if (!Double.isFinite(speedBlocksPerSecond) || speedBlocksPerSecond <= 0.0) {
      throw new IllegalArgumentException("speedBlocksPerSecond 必须为正数");
    }
    return (graph, edge, from, to) -> {
      Objects.requireNonNull(edge, "edge");
      int lengthBlocks = edge.lengthBlocks();
      if (lengthBlocks <= 0) {
        return Optional.empty();
      }
      double seconds = lengthBlocks / speedBlocksPerSecond;
      if (!Double.isFinite(seconds) || seconds < 0.0) {
        return Optional.empty();
      }
      long millis = (long) Math.round(seconds * 1000.0);
      if (millis < 0) {
        return Optional.empty();
      }
      return Optional.of(Duration.ofMillis(millis));
    };
  }

  /**
   * 叠加过滤：当边被封锁时视为无法通行，从而使路径 ETA 无法估算。
   *
   * <p>用途：当命令允许“包含封锁边”输出路径但仍希望 ETA 明确缺失时，可使用该包装。
   */
  public static RailTravelTimeModel rejectBlocked(RailTravelTimeModel delegate) {
    Objects.requireNonNull(delegate, "delegate");
    return (graph, edge, from, to) -> {
      if (graph != null && edge != null && graph.isBlocked(edge.id())) {
        return Optional.empty();
      }
      return delegate.edgeTravelTime(graph, edge, from, to);
    };
  }
}
