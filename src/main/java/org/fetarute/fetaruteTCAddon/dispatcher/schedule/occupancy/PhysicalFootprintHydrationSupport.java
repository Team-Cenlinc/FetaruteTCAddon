package org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy;

/**
 * 对单列迟加载/重组列车原子替换逻辑保护与稀疏物理 footprint 的可选能力。
 *
 * <p>与全局启动重建不同，该操作必须保留其它列车的 claim、队列与方向签名；输入只能描述现场 HOLD，不得携带运动授权。
 */
public interface PhysicalFootprintHydrationSupport {

  /**
   * 原子替换一列车的现场逻辑保护与稀疏物理 footprint。
   *
   * @param fieldSnapshot 已完整解析且区分逻辑保护与物理资源的现场快照
   * @return 是否提交，以及可审计的失败原因
   */
  StartupOccupancyReconstructionSupport.ReconstructionResult replaceTrainPhysicalFootprint(
      FieldOccupancySnapshot fieldSnapshot);
}
