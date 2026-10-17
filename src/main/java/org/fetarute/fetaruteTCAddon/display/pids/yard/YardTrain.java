package org.fetarute.fetaruteTCAddon.display.pids.yard;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking.RailFootprintCell;

/**
 * 一列调度列车此刻的位置与车次窗信息，供站场图定位车身。
 *
 * @param name 列车名
 * @param label 车次窗文字：车次号，没有时为列车名
 * @param lineColor 线路色；查不到时为空
 * @param delaySeconds 晚点秒数（早到为负）；不按表运行时为 0
 * @param cars 各节车厢所在的轨道方块，按编组顺序
 */
public record YardTrain(
    String name,
    String label,
    Optional<Integer> lineColor,
    long delaySeconds,
    List<RailFootprintCell> cars) {

  public YardTrain {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(label, "label");
    lineColor = lineColor == null ? Optional.empty() : lineColor;
    cars = List.copyOf(cars);
  }
}
