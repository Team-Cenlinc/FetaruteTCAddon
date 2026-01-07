package org.fetarute.fetaruteTCAddon.dispatcher.graph.control;

import java.util.Locale;

/**
 * 轨道速度工具类：用于在不同单位间换算并统一输出。
 *
 * <p>约定：1 block 视为 1 meter，服务器 20 tick = 1 second。
 *
 * <ul>
 *   <li>blocks/s（bps）：调度层内部使用的现实时间单位
 *   <li>blocks/tick（bpt）：TrainCarts 常用的速度语境
 *   <li>km/h：人类更直观的速度单位
 * </ul>
 *
 * 换算：
 *
 * <ul>
 *   <li>1 bps = 3.6 km/h
 *   <li>1 bpt = 20 bps = 72 km/h
 * </ul>
 */
public record RailSpeed(double blocksPerSecond) {

  private static final double TICKS_PER_SECOND = 20.0;
  private static final double KMH_PER_BPS = 3.6;

  public RailSpeed {
    if (!Double.isFinite(blocksPerSecond) || blocksPerSecond <= 0.0) {
      throw new IllegalArgumentException("blocksPerSecond 必须为正数");
    }
  }

  public static RailSpeed ofBlocksPerSecond(double blocksPerSecond) {
    return new RailSpeed(blocksPerSecond);
  }

  public static RailSpeed ofBlocksPerTick(double blocksPerTick) {
    if (!Double.isFinite(blocksPerTick) || blocksPerTick <= 0.0) {
      throw new IllegalArgumentException("blocksPerTick 必须为正数");
    }
    return new RailSpeed(blocksPerTick * TICKS_PER_SECOND);
  }

  public static RailSpeed ofKilometersPerHour(double kilometersPerHour) {
    if (!Double.isFinite(kilometersPerHour) || kilometersPerHour <= 0.0) {
      throw new IllegalArgumentException("kilometersPerHour 必须为正数");
    }
    return new RailSpeed(kilometersPerHour / KMH_PER_BPS);
  }

  public double blocksPerTick() {
    return blocksPerSecond / TICKS_PER_SECOND;
  }

  public double kilometersPerHour() {
    return blocksPerSecond * KMH_PER_BPS;
  }

  /** 统一格式化输出：同时显示 km/h、bps、bpt，便于运维对齐 TrainCarts 语境。 */
  public String formatWithAllUnits() {
    return String.format(
        Locale.ROOT,
        "%.1fkm/h (%.2fbps, %.3fbpt)",
        kilometersPerHour(),
        blocksPerSecond,
        blocksPerTick());
  }
}
