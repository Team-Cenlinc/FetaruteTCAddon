package org.fetarute.fetaruteTCAddon.dispatcher.graph.control;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * 限速设置棍携带的写入参数。
 *
 * <p>道具只保存速度与可选 TTL；起终点由玩家两次点击实时选择，最终仍走 {@link SectionSpeedService} 展开当前调度图最短路径。
 */
public record SpeedSettingStickConfig(RailSpeed speed, Optional<Duration> ttl) {

  public SpeedSettingStickConfig {
    speed = Objects.requireNonNull(speed, "speed");
    ttl = ttl == null ? Optional.empty() : ttl;
  }

  /** 解析命令输入生成设置棍配置。 */
  public static Optional<SpeedSettingStickConfig> parse(String speedRaw, Optional<String> ttlRaw) {
    Optional<RailSpeed> speed =
        Optional.ofNullable(speedRaw).map(String::trim).flatMap(RailControlParsers::parseSpeed);
    if (speed.isEmpty()) {
      return Optional.empty();
    }
    Optional<Duration> ttl = Optional.empty();
    if (ttlRaw != null && ttlRaw.isPresent()) {
      ttl = RailControlParsers.parseTtl(ttlRaw.get().trim());
      if (ttl.isEmpty()) {
        return Optional.empty();
      }
    }
    return Optional.of(new SpeedSettingStickConfig(speed.get(), ttl));
  }

  /** 计算临时限速截止时间；无 TTL 时表示长期限速。 */
  public Optional<Instant> tempUntil(Instant now) {
    Objects.requireNonNull(now, "now");
    return ttl.map(now::plus);
  }

  /** 输出供物品 Lore 与命令反馈展示的速度文本。 */
  public String speedText() {
    return speed.formatWithAllUnits();
  }

  /** 输出供物品 Lore 与命令反馈展示的 TTL 文本。 */
  public String ttlText() {
    return ttl.map(SpeedSettingStickConfig::formatDuration).orElse("-");
  }

  private static String formatDuration(Duration duration) {
    long seconds = duration.toSeconds();
    long hours = seconds / 3600;
    long minutes = (seconds % 3600) / 60;
    long rest = seconds % 60;
    StringBuilder builder = new StringBuilder();
    if (hours > 0) {
      builder.append(hours).append('h');
    }
    if (minutes > 0) {
      builder.append(minutes).append('m');
    }
    if (rest > 0 || builder.length() == 0) {
      builder.append(rest).append('s');
    }
    return builder.toString();
  }
}
