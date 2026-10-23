package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import java.util.Map;
import java.util.Optional;

/**
 * 随真实列车保存的交路恢复标记；实现方只枚举仍有实体或离线编组的列车。
 *
 * <p>不另建车辆数据库：TrainCarts 保存列车时一并保存标记，销毁列车时标记随属性消失。
 * 车辆归属在启动授权恢复阶段读取，现场占用恢复完成后才能出票；读取失败应阻止补车，而不能把未知车辆当作不存在。
 */
public interface TimetableDutyStore {
  /** 仍存在的列车名与恢复标记；无标记的车辆不在结果中。 */
  Map<String, String> read();

  /** 保存最新交路进度；empty 表示解绑、退役或不再按表运行，应删除旧标记。 */
  void write(String trainName, Optional<String> token);
}
