package org.fetarute.fetaruteTCAddon.dispatcher.graph.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.persist.RailComponentCautionRecord;

/** RailComponentCaution（连通分量 CAUTION 速度上限）仓库接口。 */
public interface RailComponentCautionRepository {

  /** 查询指定世界/分量的 caution 覆盖记录。 */
  Optional<RailComponentCautionRecord> find(UUID worldId, String componentKey);

  /** 列出指定世界下的全部 caution 覆盖记录。 */
  List<RailComponentCautionRecord> listByWorld(UUID worldId);

  /** 插入或更新 caution 覆盖记录。 */
  void upsert(RailComponentCautionRecord record);

  /** 删除指定世界/分量的 caution 覆盖。 */
  void delete(UUID worldId, String componentKey);
}
