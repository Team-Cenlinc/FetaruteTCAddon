package org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy;

/** 占用预览支持：提供“只读评估”接口，避免触发排队/状态变化。 */
public interface OccupancyPreviewSupport {

  /**
   * 预览是否允许进入资源集合（不写入状态、不入队）。
   *
   * @param request 占用请求
   * @return 预览决策
   */
  OccupancyDecision canEnterPreview(OccupancyRequest request);
}
