package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.plan;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 运行计划书与时刻表编号绑定的仓库。 */
public interface TimetablePlanRepository {

  /** 按主键查询计划书。 */
  Optional<TimetablePlan> findById(UUID id);

  /** 按运营商与 code 查询计划书。 */
  Optional<TimetablePlan> findByOperatorAndCode(UUID operatorId, String code);

  /** 运营商下的全部计划书，按 code 排序。 */
  List<TimetablePlan> listByOperator(UUID operatorId);

  /** 保存计划书（同一主键已存在则覆盖）。 */
  TimetablePlan save(TimetablePlan plan);

  /** 删除计划书；它的绑定一并删除。 */
  void delete(UUID id);

  /** 查询某个时刻表编号的绑定。 */
  Optional<TimetablePlanBinding> findBinding(UUID operatorId, String timetableCode);

  /** 运营商下的全部绑定，按时刻表编号排序。 */
  List<TimetablePlanBinding> listBindingsByOperator(UUID operatorId);

  /** 绑定了某本计划书的全部编号，按时刻表编号排序。 */
  List<TimetablePlanBinding> listBindingsByPlan(UUID planId);

  /** 绑定（编号已绑定别的计划书时改绑）。 */
  void bind(TimetablePlanBinding binding);

  /**
   * 解除绑定。
   *
   * @return 原先有绑定时为 true
   */
  boolean unbind(UUID operatorId, String timetableCode);
}
