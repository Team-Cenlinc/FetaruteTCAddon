package org.fetarute.fetaruteTCAddon.drive.license;

import java.util.List;
import java.util.Locale;
import org.bukkit.configuration.ConfigurationSection;

/**
 * 路考练习与应急演练（{@code drive.yml} 的 {@code license.training} 段）。练习跑在正式车次上，所以每一项都要防着练习把全网拖晚：
 * 晚点时不安排演练，晚点过大先撤演练，再大就交还自动运行。
 *
 * @param drill 练习中是否随机安排一次应急演练
 * @param drillMaxDelaySeconds 列车晚点超过这个值时不再安排演练
 * @param maxDelaySeconds 练习或路考中晚点超过这个值：撤掉进行中的演练故障，提示尽快恢复正点
 * @param handbackDelaySeconds 晚点超过这个值：交还自动运行，本次练习或路考不计成绩
 * @param lineLossSeconds 演练的受电中断持续多久
 * @param emergencyReactionSeconds 紧急停车演练：在这么多秒内拉到 EB 算反应及时
 * @param routes 只用这些交路代码的车次练习（以后建了专用练习线时填写）；为空时用正式车次
 */
public record TrainingConfig(
    boolean drill,
    int drillMaxDelaySeconds,
    int maxDelaySeconds,
    int handbackDelaySeconds,
    int lineLossSeconds,
    int emergencyReactionSeconds,
    List<String> routes) {

  public TrainingConfig {
    drillMaxDelaySeconds = Math.max(0, drillMaxDelaySeconds);
    maxDelaySeconds = Math.max(drillMaxDelaySeconds, maxDelaySeconds);
    handbackDelaySeconds = Math.max(maxDelaySeconds, handbackDelaySeconds);
    lineLossSeconds = Math.max(5, lineLossSeconds);
    emergencyReactionSeconds = Math.max(1, emergencyReactionSeconds);
    routes =
        routes == null
            ? List.of()
            : List.copyOf(
                routes.stream()
                    .filter(route -> route != null && !route.isBlank())
                    .map(route -> route.trim().toUpperCase(Locale.ROOT))
                    .toList());
  }

  public static TrainingConfig defaults() {
    return new TrainingConfig(true, 30, 120, 240, 15, 3, List.of());
  }

  /** 这条交路的车次能不能用来练习。 */
  public boolean allowsRoute(String routeCode) {
    return routes.isEmpty()
        || (routeCode != null && routes.contains(routeCode.trim().toUpperCase(Locale.ROOT)));
  }

  /** 从 {@code license.training} 段解析；段缺失时用默认值。 */
  public static TrainingConfig from(ConfigurationSection section) {
    TrainingConfig d = defaults();
    if (section == null) {
      return d;
    }
    return new TrainingConfig(
        section.getBoolean("drill", d.drill()),
        section.getInt("drill-max-delay-seconds", d.drillMaxDelaySeconds()),
        section.getInt("max-delay-seconds", d.maxDelaySeconds()),
        section.getInt("handback-delay-seconds", d.handbackDelaySeconds()),
        section.getInt("line-loss-seconds", d.lineLossSeconds()),
        section.getInt("emergency-reaction-seconds", d.emergencyReactionSeconds()),
        section.getStringList("routes"));
  }
}
