package org.fetarute.fetaruteTCAddon.drive.driver;

import java.util.function.Consumer;
import org.bukkit.configuration.ConfigurationSection;

/**
 * 驾驶任务与拥堵恢复的参数（{@code drive.yml} 的 {@code driver} 段）。
 *
 * <p>卡住的时间阶梯：驾驶员列车在能走的时候不走（表定驻站、调度扣车不算）累计到各档时长，依次告警、转 ATO、交还自动运行并判任务失败、 把驾驶员救援到最近的站台。
 *
 * @param warnSeconds 告警
 * @param atoSeconds 强制转 ATO
 * @param handbackSeconds 交还自动运行，任务失败
 * @param rescueSeconds 交还后列车仍不动：驾驶员下车送到站台
 * @param maxTaskMinutes 一次任务的绝对上限（分钟），超过即交还并判失败
 * @param protectionHeldTrains 全网被扣住超过 {@code protectionHeldSeconds} 的车达到这么多列、且阻挡链里有驾驶员列车时触发拥堵保护
 * @param protectionHeldSeconds 拥堵保护统计的扣车时长下限
 * @param protectionCooldownMinutes 触发拥堵保护后多少分钟内暂停接班
 * @param atoConfirmSeconds ATO 下停站结束后等驾驶员确认发车的上限，超时自动发车
 * @param atoConfirmAdvanceSeconds ATO 下停站结束前多少秒起可提前确认发车（停站一结束即放行）；0 表示只能在停站结束后确认
 * @param taskWindowMinutes 任务板列出多少分钟内的发车
 * @param congestionWarnSeconds 后方列车被驾驶员列车直接挡住超过这么久（秒）时提醒驾驶员
 * @param congestionAtoSeconds 后方列车被挡住超过这么久（秒）、驾驶员列车停着且不在表定停站时强制转 ATO
 */
public record DriverRecovery(
    int warnSeconds,
    int atoSeconds,
    int handbackSeconds,
    int rescueSeconds,
    int maxTaskMinutes,
    int protectionHeldTrains,
    int protectionHeldSeconds,
    int protectionCooldownMinutes,
    int atoConfirmSeconds,
    int atoConfirmAdvanceSeconds,
    int taskWindowMinutes,
    int congestionWarnSeconds,
    int congestionAtoSeconds) {

  /** 内置默认值。 */
  public static DriverRecovery defaults() {
    return new DriverRecovery(60, 120, 180, 300, 90, 5, 60, 15, 15, 10, 20, 60, 120);
  }

  /** 解析；缺失或非法的项回退默认值，阶梯不递增时整组回退默认值。 */
  public static DriverRecovery from(ConfigurationSection section, Consumer<String> warn) {
    DriverRecovery d = defaults();
    if (section == null) {
      return d;
    }
    Consumer<String> sink = warn != null ? warn : message -> {};
    int warnAt = positive(section, "rescue-warn-seconds", d.warnSeconds, sink);
    int atoAt = positive(section, "rescue-ato-seconds", d.atoSeconds, sink);
    int handbackAt = positive(section, "rescue-handback-seconds", d.handbackSeconds, sink);
    int rescueAt = positive(section, "rescue-teleport-seconds", d.rescueSeconds, sink);
    if (!(warnAt < atoAt && atoAt < handbackAt && handbackAt < rescueAt)) {
      sink.accept("drive.yml 的 driver.rescue-*-seconds 须依次递增，使用默认值");
      warnAt = d.warnSeconds;
      atoAt = d.atoSeconds;
      handbackAt = d.handbackSeconds;
      rescueAt = d.rescueSeconds;
    }
    int congestionWarn =
        positive(section, "congestion-warn-seconds", d.congestionWarnSeconds, sink);
    int congestionAto = positive(section, "congestion-ato-seconds", d.congestionAtoSeconds, sink);
    if (congestionWarn >= congestionAto) {
      sink.accept("drive.yml 的 driver.congestion-warn-seconds 须小于 congestion-ato-seconds，使用默认值");
      congestionWarn = d.congestionWarnSeconds;
      congestionAto = d.congestionAtoSeconds;
    }
    return new DriverRecovery(
        warnAt,
        atoAt,
        handbackAt,
        rescueAt,
        positive(section, "max-task-minutes", d.maxTaskMinutes, sink),
        positive(
            section,
            renamed(section, "protection-held-trains", "breaker-held-trains"),
            d.protectionHeldTrains,
            sink),
        positive(
            section,
            renamed(section, "protection-held-seconds", "breaker-held-seconds"),
            d.protectionHeldSeconds,
            sink),
        positive(
            section,
            renamed(section, "protection-cooldown-minutes", "breaker-cooldown-minutes"),
            d.protectionCooldownMinutes,
            sink),
        positive(section, "ato-confirm-seconds", d.atoConfirmSeconds, sink),
        nonNegative(section, "ato-confirm-advance-seconds", d.atoConfirmAdvanceSeconds, sink),
        positive(section, "task-window-minutes", d.taskWindowMinutes, sink),
        congestionWarn,
        congestionAto);
  }

  /** 新键没写而旧写法的键还在时读旧键。 */
  private static String renamed(ConfigurationSection section, String key, String legacyKey) {
    return !section.contains(key) && section.contains(legacyKey) ? legacyKey : key;
  }

  private static int nonNegative(
      ConfigurationSection section, String key, int fallback, Consumer<String> warn) {
    if (!section.contains(key)) {
      return fallback;
    }
    int value = section.getInt(key, Integer.MIN_VALUE);
    if (value < 0) {
      warn.accept("drive.yml 的 driver." + key + " 不能为负数，使用默认值 " + fallback);
      return fallback;
    }
    return value;
  }

  private static int positive(
      ConfigurationSection section, String key, int fallback, Consumer<String> warn) {
    if (!section.contains(key)) {
      return fallback;
    }
    int value = section.getInt(key, Integer.MIN_VALUE);
    if (value <= 0) {
      warn.accept("drive.yml 的 driver." + key + " 必须为正整数，使用默认值 " + fallback);
      return fallback;
    }
    return value;
  }
}
