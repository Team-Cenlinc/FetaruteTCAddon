package org.fetarute.fetaruteTCAddon.drive.sound;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import org.bukkit.configuration.ConfigurationSection;

/**
 * 驾驶提示音的配置（{@code drive.yml} 的 {@code sounds} 段）。
 *
 * @param enabled 总开关
 * @param hornCooldownTicks 两次鸣笛至少间隔多少 tick
 * @param specs 各提示音；没有列出的用内置默认值
 */
public record DriveSoundConfig(boolean enabled, int hornCooldownTicks, Map<DriveCue, Spec> specs) {

  /**
   * 一种提示音。
   *
   * @param sound 音效键（如 {@code minecraft:block.note_block.bell}，材质包里的自定义音效也可以）；空串表示关闭
   * @param volume 音量
   * @param pitch 音高（0.5–2）
   */
  public record Spec(String sound, float volume, float pitch) {
    public Spec {
      sound = sound == null ? "" : sound.trim();
    }

    /** 是否关闭了这种提示音。 */
    public boolean muted() {
      return sound.isEmpty() || !(volume > 0.0f);
    }
  }

  private static final int TICKS_PER_SECOND = 20;

  public DriveSoundConfig {
    EnumMap<DriveCue, Spec> all = new EnumMap<>(DriveCue.class);
    for (DriveCue cue : DriveCue.values()) {
      all.put(cue, cue.defaultSpec());
    }
    if (specs != null) {
      all.putAll(specs);
    }
    specs = Map.copyOf(all);
    hornCooldownTicks = Math.max(0, hornCooldownTicks);
  }

  /** 内置默认值。 */
  public static DriveSoundConfig defaults() {
    return new DriveSoundConfig(true, 3 * TICKS_PER_SECOND, Map.of());
  }

  /** 某种提示音；总开关关闭或该项被关闭时为空。 */
  public Optional<Spec> spec(DriveCue cue) {
    Objects.requireNonNull(cue, "cue");
    Spec spec = specs.get(cue);
    return !enabled || spec == null || spec.muted() ? Optional.empty() : Optional.of(spec);
  }

  /**
   * 从 {@code sounds} 段解析；段缺失时返回默认值，非法项回退默认值并提示。
   *
   * @param section {@code drive.yml} 的 {@code sounds} 段，可为空
   */
  public static DriveSoundConfig from(ConfigurationSection section, Consumer<String> warn) {
    DriveSoundConfig d = defaults();
    if (section == null) {
      return d;
    }
    Consumer<String> sink = warn != null ? warn : message -> {};
    double cooldown = section.getDouble("horn-cooldown-seconds", d.hornCooldownTicks / 20.0);
    if (!Double.isFinite(cooldown) || cooldown < 0.0) {
      sink.accept("drive.yml 的 sounds.horn-cooldown-seconds 不能为负数，使用默认值");
      cooldown = d.hornCooldownTicks / 20.0;
    }
    EnumMap<DriveCue, Spec> specs = new EnumMap<>(DriveCue.class);
    for (DriveCue cue : DriveCue.values()) {
      Spec fallback = cue.defaultSpec();
      ConfigurationSection entry = section.getConfigurationSection(cue.configKey());
      if (entry == null) {
        if (section.isString(cue.configKey())) {
          // 简写：只给音效键。
          specs.put(
              cue,
              new Spec(section.getString(cue.configKey()), fallback.volume(), fallback.pitch()));
        }
        continue;
      }
      float volume = (float) entry.getDouble("volume", fallback.volume());
      float pitch = (float) entry.getDouble("pitch", fallback.pitch());
      if (!Float.isFinite(volume) || volume < 0.0f) {
        sink.accept("drive.yml 的 sounds." + cue.configKey() + ".volume 不能为负数，使用默认值");
        volume = fallback.volume();
      }
      if (!Float.isFinite(pitch) || pitch < 0.5f || pitch > 2.0f) {
        sink.accept("drive.yml 的 sounds." + cue.configKey() + ".pitch 须在 0.5–2 之间，使用默认值");
        pitch = fallback.pitch();
      }
      specs.put(cue, new Spec(entry.getString("key", fallback.sound()), volume, pitch));
    }
    return new DriveSoundConfig(
        section.getBoolean("enabled", d.enabled),
        (int) Math.round(cooldown * TICKS_PER_SECOND),
        specs);
  }
}
