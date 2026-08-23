package org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy;

import java.util.List;

/**
 * 启动现场占用的原子重建能力。
 *
 * <p>该接口接收完整的现场保护请求集合，并在单个提交点替换占用快照。重建请求只能描述已经存在的物理占用或保护范围，不能携带新的前进授权；即使多个列车报告同一资源，实现也必须保留全部现场事实，交由后续授权阶段
 * fail-closed 处理。
 */
public interface StartupOccupancyReconstructionSupport {

  /**
   * 原子替换启动现场逻辑保护与稀疏物理占用快照。
   *
   * @param fieldSnapshots 全部受管现场列车的逻辑保护与稀疏物理事实
   * @return 提交结果；失败时原快照必须保持不变
   */
  ReconstructionResult reconstructPhysicalSnapshot(List<FieldOccupancySnapshot> fieldSnapshots);

  /** 启动现场快照提交结果。 */
  record ReconstructionResult(boolean committed, String reason, int trainCount, int claimCount) {

    public ReconstructionResult {
      reason = reason == null || reason.isBlank() ? "-" : reason.trim();
      trainCount = Math.max(0, trainCount);
      claimCount = Math.max(0, claimCount);
    }

    /** 构造成功结果。 */
    public static ReconstructionResult committed(int trainCount, int claimCount) {
      return new ReconstructionResult(true, "committed", trainCount, claimCount);
    }

    /** 构造失败结果。 */
    public static ReconstructionResult rejected(String reason) {
      return new ReconstructionResult(false, reason, 0, 0);
    }
  }
}
