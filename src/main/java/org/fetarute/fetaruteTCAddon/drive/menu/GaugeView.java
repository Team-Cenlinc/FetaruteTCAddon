package org.fetarute.fetaruteTCAddon.drive.menu;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 菜单上一块压力表此刻的样子。
 *
 * <p>表计用物品的耐久条当指针：耐久条满格表示读数到量程上限，空格表示 0。原版只在物品受损（损耗值大于 0）时画耐久条，所以损耗值最小取 1，
 * 满量程时耐久条仍然显示为满格。本类不依赖服务器对象，便于单测。
 *
 * @param indicator 哪块表
 * @param valueKpa 读数（kPa）
 * @param rangeKpa 量程上限（kPa）
 * @param band 读数所在的区段
 * @param detailKey 说明一行的语言键
 * @param detailValues 说明里的占位符
 */
public record GaugeView(
    MenuIndicator indicator,
    double valueKpa,
    double rangeKpa,
    Band band,
    String detailKey,
    Map<String, String> detailValues) {

  /** 读数区段。 */
  public enum Band {
    /** 正常。 */
    NORMAL,
    /** 偏低，需要留意。 */
    WARNING,
    /** 异常（封锁牵引、失压、漏泄）。 */
    ALARM
  }

  /** 表计物品的最大耐久，即指针的分辨率。 */
  public static final int MAX_DAMAGE = 1000;

  public GaugeView {
    Objects.requireNonNull(indicator, "indicator");
    Objects.requireNonNull(band, "band");
    Objects.requireNonNull(detailKey, "detailKey");
    if (indicator == MenuIndicator.FAULTS) {
      throw new IllegalArgumentException("故障指示不是压力表");
    }
    if (!(rangeKpa > 0.0)) {
      throw new IllegalArgumentException("量程必须为正数");
    }
    detailValues = detailValues == null ? Map.of() : Map.copyOf(detailValues);
  }

  /** 读数占量程的比例（0–1）。 */
  public double fraction() {
    if (!Double.isFinite(valueKpa)) {
      return 0.0;
    }
    return Math.max(0.0, Math.min(1.0, valueKpa / rangeKpa));
  }

  /** 物品的损耗值：读数越高损耗越小，耐久条越满；最小为 1，保证耐久条总是画出来。 */
  public int damage() {
    long filled = Math.round(fraction() * (MAX_DAMAGE - 1));
    return (int) Math.max(1L, Math.min(MAX_DAMAGE, MAX_DAMAGE - filled));
  }

  /** 读数取整（kPa）。 */
  public long roundedKpa() {
    return Math.round(Math.max(0.0, valueKpa));
  }

  /** 表计在语言键与贴图名里的写法。 */
  public String kindKey() {
    return switch (indicator) {
      case MAIN_RESERVOIR -> "main-reservoir";
      case BRAKE_CYLINDER -> "brake-cylinder";
      case BRAKE_PIPE -> "brake-pipe";
      case FAULTS -> throw new IllegalStateException("故障指示不是压力表");
    };
  }

  /** 名称的语言键：按区段着色。 */
  public String nameKey() {
    return "drive.menu.gauge." + kindKey() + "." + band.name().toLowerCase(Locale.ROOT);
  }

  /** 贴图键（{@code fetarute:drive/} 之后的部分）：异常时用故障贴图。 */
  public String modelKey() {
    String name =
        switch (indicator) {
          case MAIN_RESERVOIR -> "gauge_mr";
          case BRAKE_CYLINDER -> "gauge_bc";
          case BRAKE_PIPE -> "gauge_bp";
          case FAULTS -> throw new IllegalStateException("故障指示不是压力表");
        };
    return MenuLayout.MODEL_PREFIX + name + (band == Band.ALARM ? "_fault" : "_on");
  }

  /** 量程上限取整到百 kPa。 */
  public static double rangeFor(double maxKpa) {
    return Math.max(100.0, Math.ceil(maxKpa / 100.0) * 100.0);
  }
}
