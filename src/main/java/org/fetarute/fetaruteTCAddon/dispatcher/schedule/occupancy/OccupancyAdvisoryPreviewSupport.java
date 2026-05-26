package org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy;

import java.util.List;

/** 提供不写状态的 advisory lookahead 风险扫描。 */
public interface OccupancyAdvisoryPreviewSupport {

  /**
   * 扫描 LOOKAHEAD_PREVIEW 资源上的前方风险。
   *
   * <p>实现不得 acquire、入队、释放资源或把风险转换成 {@code allowed=false}。调用方负责将返回风险 staged 为黄灯。
   */
  List<AdvisoryRisk> scanAdvisoryRisks(OccupancyRequest request);
}
