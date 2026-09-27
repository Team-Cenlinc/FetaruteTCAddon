package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.Timetable;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableStatus;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.scope.TimetableBaseline;

/**
 * 时刻表仓库。
 *
 * <p>时刻表、它的发车表与车辆交路是同一个聚合：{@code save} 必须整体替换 trips 与 duties，读取也必须整体带回。
 * 半份时刻表对运行时是有害的——有表头没车次会让一条线进入"按表运行"却一趟车都发不出来； 有车次没 duty 会让"每辆车最终都会回库"这条不变量失去执行依据。
 */
public interface TimetableRepository {

  /** 按 ID 查询（含 trips 与 duties）。 */
  Optional<Timetable> findById(UUID id);

  /** 按线路 + code 查询（含 trips 与 duties）。 */
  Optional<Timetable> findByLineAndCode(UUID lineId, String code);

  /** 列出某条线路下的全部时刻表（含 trips 与 duties）。 */
  List<Timetable> listByLine(UUID lineId);

  /** 列出全网处于 PUBLISHED 状态的时刻表（含 trips 与 duties），供运行时预热。 */
  List<Timetable> listPublished();

  /** 保存时刻表及其发车表与车辆交路（存在则整体覆盖），整份在一个事务里写完。 */
  Timetable save(Timetable timetable);

  /**
   * 只改发布状态与更新时刻，不动车次与交路。
   *
   * <p>发布、撤下只是翻一个状态；走 {@link #save} 会把几千行车次与交路删掉重插。
   *
   * @param id 时刻表
   * @param status 新状态
   * @param updatedAt 更新时刻（publish 重检靠它判断邻表有没有变）
   * @return 是否找到这份表
   */
  boolean updateStatus(UUID id, TimetableStatus status, Instant updatedAt);

  /** 删除时刻表及其发车表与车辆交路。 */
  void delete(UUID id);

  /** 替换一份表的邻表基线（build 与 publish 重检后写入）。 */
  void replaceBaselines(UUID timetableId, List<TimetableBaseline> baselines);

  /** 读一份表的邻表基线。 */
  List<TimetableBaseline> listBaselines(UUID timetableId);
}
