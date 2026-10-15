package org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy;

/**
 * 同股道列车对：拆分（或推车联挂）后两列车同处一段轨道，现有闭塞表示不了“同一段轨道上前后两列车”。朝自己离开方向（背向对方）走的一方， 对方在两车共用资源上的占用不挡它；朝对方走的一方照常被挡。
 *
 * <p>判定须只读、快速（在占用判定热路径上），信息不全时一律返回 {@code false}（照常挡）。
 */
@FunctionalInterface
public interface CoLocationPolicy {

  /** 没有同股道列车对。 */
  CoLocationPolicy NONE = (request, resource, claim) -> false;

  /**
   * 对方这条占用是否不挡请求方。
   *
   * @param request 请求方的占用请求
   * @param resource 资源
   * @param claim 别的列车在这个资源上的占用
   * @return 请求方与占用方是同股道列车对、资源在两车共用的资源里、且请求方朝背向对方的方向走时为 {@code true}
   */
  boolean exempts(OccupancyRequest request, OccupancyResource resource, OccupancyClaim claim);
}
