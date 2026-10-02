package org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy;

import java.util.Objects;
import java.util.Set;

/**
 * 单列车的启动现场占用快照。
 *
 * <p>{@link #request()} 描述由规范 Route/Node 模型推导出的完整保护窗口；{@link #physicalResources()}
 * 只标记实时车体观测实际命中的稀疏物理联锁资源。两者分离后，普通 Edge/Node 可以保持 {@link ClaimRole#HOLD_ONLY}，而不会被误报为坐标级物理事实。
 *
 * @param request 不携带运动授权的现场保护请求
 * @param physicalResources 已在实时车体上观测到的稀疏物理资源，必须是 request 资源的子集
 */
public record FieldOccupancySnapshot(
    OccupancyRequest request, Set<OccupancyResource> physicalResources) {

  public FieldOccupancySnapshot {
    request = Objects.requireNonNull(request, "request");
    physicalResources = physicalResources == null ? Set.of() : Set.copyOf(physicalResources);
  }

  /** 将请求中的全部资源显式标记为现场物理事实，供已验证的兼容调用方使用。 */
  public static FieldOccupancySnapshot allPhysical(OccupancyRequest request) {
    OccupancyRequest resolved = Objects.requireNonNull(request, "request");
    return new FieldOccupancySnapshot(resolved, Set.copyOf(resolved.resourceList()));
  }

  /** 返回指定资源在本快照中应使用的 claim 角色。 */
  public ClaimRole roleFor(OccupancyResource resource) {
    if (physicalResources.contains(resource)) {
      return ClaimRole.PHYSICAL_FOOTPRINT;
    }
    return ClaimRole.fromIntent(request.intentFor(resource));
  }
}
