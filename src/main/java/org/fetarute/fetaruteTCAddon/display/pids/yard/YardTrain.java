package org.fetarute.fetaruteTCAddon.display.pids.yard;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking.RailFootprintCell;

/**
 * 一列调度列车此刻的位置，供站场图定位车身；车次窗信息只在车身落在站场图上时才查，查过一次就记住。
 *
 * @param name 列车名
 * @param cars 各节车厢所在的轨道方块，按编组顺序
 * @param details 车次窗信息
 */
public record YardTrain(String name, List<RailFootprintCell> cars, Supplier<Details> details) {

  public YardTrain {
    Objects.requireNonNull(name, "name");
    cars = List.copyOf(cars);
    Objects.requireNonNull(details, "details");
    details = details instanceof Once ? details : new Once(details);
  }

  /** 车次窗信息已知的列车。 */
  public YardTrain(
      String name,
      String label,
      Optional<Integer> lineColor,
      long delaySeconds,
      List<RailFootprintCell> cars) {
    this(name, cars, () -> new Details(label, lineColor, delaySeconds));
  }

  /**
   * 车次窗信息。
   *
   * @param label 车次窗文字：车次号，没有时为列车名
   * @param lineColor 线路色；查不到时为空
   * @param delaySeconds 晚点秒数（早到为负）；不按表运行时为 0
   */
  public record Details(String label, Optional<Integer> lineColor, long delaySeconds) {
    public Details {
      Objects.requireNonNull(label, "label");
      lineColor = lineColor == null ? Optional.empty() : lineColor;
    }
  }

  /** 第一次取时才查，之后记住结果（只在主线程用）。 */
  private static final class Once implements Supplier<Details> {
    private Supplier<Details> source;
    private Details value;

    Once(Supplier<Details> source) {
      this.source = source;
    }

    @Override
    public Details get() {
      if (value == null) {
        value = Objects.requireNonNull(source.get(), "details");
        source = null;
      }
      return value;
    }
  }
}
