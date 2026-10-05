package org.fetarute.fetaruteTCAddon.dispatcher.consist;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 编组方案仓库。 */
public interface ConsistPlanRepository {

  /** 按主键查询。 */
  Optional<ConsistPlan> findById(UUID id);

  /** 按运营商与方案名查询；名字不区分大小写。 */
  Optional<ConsistPlan> findByOperatorAndName(UUID operatorId, String name);

  /** 运营商下的全部方案，按名字排序。 */
  List<ConsistPlan> listByOperator(UUID operatorId);

  /** 全部方案。 */
  List<ConsistPlan> listAll();

  /** 保存（存在则覆盖）。 */
  ConsistPlan save(ConsistPlan plan);

  /** 删除。 */
  void delete(UUID id);
}
