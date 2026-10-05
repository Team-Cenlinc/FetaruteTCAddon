package org.fetarute.fetaruteTCAddon.dispatcher.consist;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.config.TrainConfig;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.config.TrainType;

/**
 * 一个车型的档案：出车、控车、估算都读这一份。
 *
 * @param pattern 编组写法（规整后的书写形式，出车用它）
 * @param cars 节数
 * @param lengthBlocks 车身总长（格，含车钩间隙）
 * @param type 车种
 * @param typeSource 车种取自哪里
 * @param accelBps2 加速度（格/秒²）
 * @param decelBps2 常用制动减速度（格/秒²）
 * @param maxSpeedBps 车型最高速度；为空表示不限
 * @param displayName 显示名；为空时由展示层按节数生成
 * @param savedTrain 写法本身就是一个 TrainCarts 存车名
 * @param spawnLimit 存车的出车上限（TrainCarts {@code spawnlimit}）；为空表示不限
 */
public record ConsistProfile(
    String pattern,
    int cars,
    double lengthBlocks,
    TrainType type,
    TypeSource typeSource,
    double accelBps2,
    double decelBps2,
    OptionalDouble maxSpeedBps,
    Optional<String> displayName,
    boolean savedTrain,
    OptionalInt spawnLimit) {

  /** 车种的来源，报告里要写明，方便判断该改方案还是改存车。 */
  public enum TypeSource {
    /** 编组方案里的覆盖项。 */
    PLAN,
    /** 存车车厢上的 {@code FTA_TRAIN_TYPE} 标签。 */
    TAG,
    /** 按编组名里的关键字推断。 */
    NAME,
    /** 配置的默认车种。 */
    DEFAULT
  }

  public ConsistProfile {
    Objects.requireNonNull(pattern, "pattern");
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(typeSource, "typeSource");
    Objects.requireNonNull(maxSpeedBps, "maxSpeedBps");
    Objects.requireNonNull(displayName, "displayName");
    Objects.requireNonNull(spawnLimit, "spawnLimit");
    if (cars <= 0) {
      throw new IllegalArgumentException("cars 必须为正数");
    }
  }

  /** 比较用的车型键。 */
  public String key() {
    return ConsistKey.of(pattern).orElseThrow();
  }

  /** 控车与估算用的加减速与最高速度。 */
  public TrainConfig trainConfig() {
    return new TrainConfig(type, accelBps2, decelBps2, maxSpeedBps);
  }
}
