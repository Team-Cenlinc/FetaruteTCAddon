package org.fetarute.fetaruteTCAddon.dispatcher.consist;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.config.TrainType;

/**
 * 编组方案里对一个车型的覆盖项。没写的项为空，按存车标签与配置取。
 *
 * <p>覆盖项描述的是车本身：同一个编组写法在同一公司的各份方案里必须写得一样，否则复用到别的 route 上的车， 控车与估算会按两套参数走。
 *
 * @param displayName 显示名；为空时由展示层按节数生成
 * @param type 车种
 * @param accelBps2 加速度（格/秒²）
 * @param decelBps2 常用制动减速度（格/秒²）
 * @param maxSpeedBps 车型最高速度（格/秒）
 */
public record ConsistOverrides(
    Optional<String> displayName,
    Optional<TrainType> type,
    OptionalDouble accelBps2,
    OptionalDouble decelBps2,
    OptionalDouble maxSpeedBps) {

  /** 没有任何覆盖。 */
  public static final ConsistOverrides NONE =
      new ConsistOverrides(
          Optional.empty(),
          Optional.empty(),
          OptionalDouble.empty(),
          OptionalDouble.empty(),
          OptionalDouble.empty());

  public ConsistOverrides {
    Objects.requireNonNull(displayName, "displayName");
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(accelBps2, "accelBps2");
    Objects.requireNonNull(decelBps2, "decelBps2");
    Objects.requireNonNull(maxSpeedBps, "maxSpeedBps");
    displayName = displayName.map(String::trim).filter(name -> !name.isEmpty());
    requirePositive(accelBps2, "accelBps2");
    requirePositive(decelBps2, "decelBps2");
    requirePositive(maxSpeedBps, "maxSpeedBps");
  }

  /** 一项都没写。 */
  public boolean isEmpty() {
    return equals(NONE);
  }

  private static void requirePositive(OptionalDouble value, String name) {
    if (value.isPresent() && !(Double.isFinite(value.getAsDouble()) && value.getAsDouble() > 0.0)) {
      throw new IllegalArgumentException(name + " 必须为正数");
    }
  }
}
