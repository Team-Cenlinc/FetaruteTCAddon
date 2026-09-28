package org.fetarute.fetaruteTCAddon.dispatcher.runtime.config;

import java.util.Locale;
import java.util.Optional;

/**
 * 车种与其加减速预设。
 *
 * <p>单位是格/秒²，1 格按 1 米计，数值取各类车型低速段的平均起动加速度与常用制动减速度（不是紧急制动）。配置 {@code train.types.<key>} 可逐项覆盖； 编表与
 * ETA 的运行曲线按默认车种取值，改动后需重新 build 时刻表。
 */
public enum TrainType {
  /** 通勤/市域电动车组。 */
  EMU(0.9, 1.0),
  /** 内燃动车组：牵引功率小，起步慢于电动车组。 */
  DMU(0.6, 0.9),
  /** 内燃机车推拉运行：机车牵引整列车厢，起步最慢，长编组制动也较缓。 */
  DIESEL_PUSH_PULL(0.35, 0.7),
  /** 电力机车牵引：功率大于内燃机车，但仍是集中动力，远不及动车组。 */
  ELECTRIC_LOCO(0.5, 0.8),
  /** 地铁/轻轨型电动车组（含 tram-train）：站距短、起停频繁，加减速最高。 */
  METRO(1.1, 1.2);

  private final double presetAccelBps2;
  private final double presetDecelBps2;

  TrainType(double presetAccelBps2, double presetDecelBps2) {
    this.presetAccelBps2 = presetAccelBps2;
    this.presetDecelBps2 = presetDecelBps2;
  }

  /** 预设加速度（格/秒²）。 */
  public double presetAccelBps2() {
    return presetAccelBps2;
  }

  /** 预设常用制动减速度（格/秒²）。 */
  public double presetDecelBps2() {
    return presetDecelBps2;
  }

  public static Optional<TrainType> parse(String raw) {
    if (raw == null) {
      return Optional.empty();
    }
    String normalized = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_');
    if (normalized.isEmpty()) {
      return Optional.empty();
    }
    try {
      return Optional.of(TrainType.valueOf(normalized));
    } catch (IllegalArgumentException ex) {
      return Optional.empty();
    }
  }

  /** 配置键与列车标签里的写法（小写）。 */
  public String key() {
    return name().toLowerCase(Locale.ROOT);
  }
}
