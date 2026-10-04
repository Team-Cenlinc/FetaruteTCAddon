package org.fetarute.fetaruteTCAddon.drive.setup;

import java.util.Locale;
import java.util.Optional;

/**
 * 列车的受电（动力来源）方式，决定启动流程里“受电”一步做什么、播放哪个模型动画。
 *
 * <p>由列车标签 {@code FTA_TRAIN_POWER} 指定，缺省取 {@code drive.yml} 的 {@code default-power}。
 */
public enum PowerSupply {
  /** 受电弓，升到 5 米接触网高度（模型动画 {@code ptg5}）。 */
  PTG5("ptg5", "ptg5", true),
  /** 受电弓，升到 6 米接触网高度（模型动画 {@code ptg6}）。 */
  PTG6("ptg6", "ptg6", true),
  /** 集电靴，第三轨受电，没有模型动画。 */
  SHOE("shoe", null, true),
  /** 内燃动力：受电一步改为启动发动机，没有主断路器。 */
  DIESEL("diesel", null, false),
  /** 超级电容：站间靠车上储能行驶，停站开门时充电。受电一步改为投入电容（没有动画）；升降弓由站台上的 TC 牌子负责，插件不播。 */
  SUPERCAP("supercap", null, true);

  private final String key;
  private final String animation;
  private final boolean electric;

  PowerSupply(String key, String animation, boolean electric) {
    this.key = key;
    this.animation = animation;
    this.electric = electric;
  }

  /** 标签与配置里的写法，也用作贴图与语言键。 */
  public String key() {
    return key;
  }

  /** 受电时播放的模型动画名；没有动画时为空。 */
  public Optional<String> animation() {
    return Optional.ofNullable(animation);
  }

  /** 是否靠车上储能行驶、要到站充电。 */
  public boolean storesEnergy() {
    return this == SUPERCAP;
  }

  /** 是否为电力牵引（有主断路器）。 */
  public boolean electric() {
    return electric;
  }

  /**
   * 解析受电方式（不区分大小写）。
   *
   * @return 对应方式；空白或无法识别时为空
   */
  public static Optional<PowerSupply> parse(String raw) {
    if (raw == null || raw.isBlank()) {
      return Optional.empty();
    }
    String normalized = raw.trim().toLowerCase(Locale.ROOT);
    for (PowerSupply supply : values()) {
      if (supply.key.equals(normalized)) {
        return Optional.of(supply);
      }
    }
    return switch (normalized) {
      case "third-rail", "third_rail", "thirdrail" -> Optional.of(SHOE);
      case "engine" -> Optional.of(DIESEL);
      case "super-capacitor", "super_capacitor", "supercapacitor", "capacitor" -> Optional.of(
          SUPERCAP);
      default -> Optional.empty();
    };
  }
}
