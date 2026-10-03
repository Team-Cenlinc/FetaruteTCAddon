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
 * @param breakerHeldTrains 全网被扣住超过 {@code breakerHeldSeconds} 的车达到这么多列、且阻挡链里有驾驶员列车时熔断
 * @param breakerHeldSeconds 熔断统计的扣车时长下限
 * @param breakerCooldownMinutes 熔断后多少分钟内暂停接班
 * @param atoConfirmSeconds ATO 下停站结束后等驾驶员确认发车的上限，超时自动发车
 * @param taskWindowMinutes 任务板列出多少分钟内的发车
 */
public record DriverRecovery(
    int warnSeconds,
    int atoSeconds,
    int handbackSeconds,
    int rescueSeconds,
    int maxTaskMinutes,
    int breakerHeldTrains,
    int breakerHeldSeconds,
    int breakerCooldownMinutes,
    int atoConfirmSeconds,
    int taskWindowMinutes) {

  /** 内置默认值。 */
  public static DriverRecovery defaults() {
    return new DriverRecovery(60, 120, 180, 300, 90, 5, 60, 15, 15, 20);
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
    return new DriverRecovery(
        warnAt,
        atoAt,
        handbackAt,
        rescueAt,
        positive(section, "max-task-minutes", d.maxTaskMinutes, sink),
        positive(section, "breaker-held-trains", d.breakerHeldTrains, sink),
        positive(section, "breaker-held-seconds", d.breakerHeldSeconds, sink),
        positive(section, "breaker-cooldown-minutes", d.breakerCooldownMinutes, sink),
        positive(section, "ato-confirm-seconds", d.atoConfirmSeconds, sink),
        positive(section, "task-window-minutes", d.taskWindowMinutes, sink));
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
