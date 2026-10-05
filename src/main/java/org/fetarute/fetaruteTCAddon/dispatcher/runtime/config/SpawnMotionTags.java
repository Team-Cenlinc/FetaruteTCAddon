package org.fetarute.fetaruteTCAddon.dispatcher.runtime.config;

import com.bergerkiller.bukkit.tc.properties.TrainProperties;
import java.util.Map;
import org.fetarute.fetaruteTCAddon.config.ConfigManager;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.TrainTagHelper;

/**
 * 出车、折返复用时把列车的加减速写进标签，驾驶员接管后看得到、也按同一组数开车。
 *
 * <h2>写什么</h2>
 *
 * <p>写的是调度此刻为这列车解析出的值：车种（{@code FTA_TRAIN_TYPE}，缺省为 {@code train.default-type}）在 {@code
 * train.types} 里的加减速。编表与 ETA 的运行曲线取的是默认车种的同一组数（{@code
 * RunCurveModel.Settings#fromConfig}），所以没打车种标签的列车写下的就是编表口径；模板列车自带车种标签时写的是该车种的数，与自动运行一致，不改写成编表口径。
 *
 * <h2>不改变自动运行</h2>
 *
 * <ul>
 *   <li>写入的标签带 {@link TrainConfigResolver#TAG_TRAIN_CONFIG_SOURCE}{@code =}{@link
 *       TrainConfigResolver#SOURCE_SPAWN}，解析时按车种配置取值（见 {@link
 *       TrainConfigResolver#resolve}）：写下时两者相同，配置重载后也不会把旧值冻结在车上；
 *   <li>列车已有用户设定的加减速标签（{@link TrainConfigResolver#hasUserMotionTags}）时一个标签也不写；
 *   <li>先写来源标记、再写数值：中途失败时留下的数值标签仍被识别为出车写入，不会变成"用户设定"而被冻结。
 * </ul>
 *
 * <p>模板列车若是从出车的列车另存的，会连来源标记一起复制，下次出车照样刷新，不当作用户设定。
 *
 * <p>本类只读写标签，不碰服务器对象，任何异常都吞掉并报 {@link Outcome#FAILED}：出车事务里可失败的初始化不得冒泡。
 */
public final class SpawnMotionTags {

  private static final TrainConfigResolver RESOLVER = new TrainConfigResolver();

  private SpawnMotionTags() {}

  /** 一次写入的结果。 */
  public enum Outcome {
    /** 已写入（数值未变时标签保持原样）。 */
    STAMPED,
    /** 列车带有用户设定的加减速标签，未写。 */
    USER_OWNED,
    /** 缺少列车或配置，未写。 */
    SKIPPED,
    /** 读写标签时出错，可能只写了一部分。 */
    FAILED
  }

  /**
   * 按当前配置写入加减速标签。
   *
   * @param properties 出车或复用的列车
   * @param config 当前配置；为空时不写
   */
  public static Outcome stamp(TrainProperties properties, ConfigManager.ConfigView config) {
    if (config == null) {
      return Outcome.SKIPPED;
    }
    return stamp(properties, config.trainConfigSettings());
  }

  /**
   * 按车种配置写入加减速标签。
   *
   * @param properties 出车或复用的列车
   * @param settings 车种配置；为空时不写
   */
  public static Outcome stamp(
      TrainProperties properties, ConfigManager.TrainConfigSettings settings) {
    if (properties == null || settings == null) {
      return Outcome.SKIPPED;
    }
    try {
      if (RESOLVER.hasUserMotionTags(properties)) {
        return Outcome.USER_OWNED;
      }
      TrainType type = RESOLVER.readType(properties).orElse(settings.defaultTrainType());
      ConfigManager.TrainTypeSettings motion = settings.forType(type);
      TrainTagHelper.writeTag(
          properties,
          TrainConfigResolver.TAG_TRAIN_CONFIG_SOURCE,
          TrainConfigResolver.SOURCE_SPAWN);
      TrainTagHelper.writeTag(
          properties, TrainConfigResolver.TAG_TRAIN_ACCEL_BPS2, format(motion.accelBps2()));
      TrainTagHelper.writeTag(
          properties, TrainConfigResolver.TAG_TRAIN_DECEL_BPS2, format(motion.decelBps2()));
      return Outcome.STAMPED;
    } catch (RuntimeException | LinkageError ex) {
      return Outcome.FAILED;
    }
  }

  /**
   * 出车时先写编组方案给的标签（车型与覆盖项），再按车型写加减速。方案覆盖了加减速时这两项归方案所有：撤掉出车来源标记（从出车列车另存的模板会带着），
   * 模板带来的、方案没覆盖的那一项出车值一并撤掉改按车种配置，之后出车、折返复用、配置重载都不再改写。
   *
   * @param consistTags 编组方案给这列车的标签；为空时等同 {@link #stamp(TrainProperties, ConfigManager.ConfigView)}
   */
  public static Outcome stampWithConsist(
      TrainProperties properties,
      ConfigManager.ConfigView config,
      Map<String, String> consistTags) {
    if (properties == null) {
      return Outcome.SKIPPED;
    }
    if (consistTags == null || consistTags.isEmpty()) {
      return stamp(properties, config);
    }
    try {
      boolean wasStamped = RESOLVER.isSpawnStamped(properties);
      consistTags.forEach((key, value) -> TrainTagHelper.writeTag(properties, key, value));
      boolean accel = consistTags.containsKey(TrainConfigResolver.TAG_TRAIN_ACCEL_BPS2);
      boolean decel = consistTags.containsKey(TrainConfigResolver.TAG_TRAIN_DECEL_BPS2);
      if (!accel && !decel) {
        return stamp(properties, config);
      }
      if (wasStamped) {
        if (!accel) {
          TrainTagHelper.removeTagKey(properties, TrainConfigResolver.TAG_TRAIN_ACCEL_BPS2);
        }
        if (!decel) {
          TrainTagHelper.removeTagKey(properties, TrainConfigResolver.TAG_TRAIN_DECEL_BPS2);
        }
      }
      TrainTagHelper.removeTagKey(properties, TrainConfigResolver.TAG_TRAIN_CONFIG_SOURCE);
      return Outcome.USER_OWNED;
    } catch (RuntimeException | LinkageError ex) {
      return Outcome.FAILED;
    }
  }

  /**
   * 配置重载后刷新出车写入的标签；用户设定的、从未写入过的列车都不动。
   *
   * @param trains 要检查的列车
   * @param config 重载后的配置；为空时不写
   * @return 重新写入的列车数（数值未变的也算）
   */
  public static int refreshStamped(
      Iterable<TrainProperties> trains, ConfigManager.ConfigView config) {
    if (trains == null || config == null) {
      return 0;
    }
    int refreshed = 0;
    for (TrainProperties properties : trains) {
      if (properties == null || !isStampedSafely(properties)) {
        continue;
      }
      if (stamp(properties, config.trainConfigSettings()) == Outcome.STAMPED) {
        refreshed++;
      }
    }
    return refreshed;
  }

  /**
   * 标签里的写法。{@link Double#toString} 给出能还原为同一个 double 的最短十进制，解析回来与配置值逐位相等。
   *
   * @param value 加速度或减速度（格/秒²）
   */
  static String format(double value) {
    return Double.toString(value);
  }

  private static boolean isStampedSafely(TrainProperties properties) {
    try {
      return RESOLVER.isSpawnStamped(properties);
    } catch (RuntimeException | LinkageError ex) {
      return false;
    }
  }
}
