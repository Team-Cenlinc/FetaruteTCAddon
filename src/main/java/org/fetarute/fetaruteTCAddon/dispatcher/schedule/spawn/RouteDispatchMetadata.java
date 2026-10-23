package org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn;

import java.util.Map;
import org.fetarute.fetaruteTCAddon.company.model.Route;
import org.fetarute.fetaruteTCAddon.company.model.RouteOperationType;

/**
 * 出入库交路的需求来源：停站决定是否带客，本设置只决定是否随运营车辆交路调度。
 *
 * <p>缺省关闭以兼容周期出库。开启的 CREATE/RETURN 不要求 baseline，不独立生成周期票； 出票由时刻表的出入库及替补车辆机制负责，STOP 与 dwell 保持原样。
 */
public final class RouteDispatchMetadata {
  public static final String KEY = "dispatch_by_operation";

  private RouteDispatchMetadata() {}

  /** 仅显式 true 开启；无法识别或缺省保持现有调度方式。 */
  public static boolean read(Map<String, Object> metadata) {
    return LineSpawnMetadata.readBoolean(metadata, KEY).orElse(false);
  }

  /** 运营 route 本身不能作为出入库服务，调用方校验配置时应报告不支持的类型。 */
  public static boolean supports(RouteOperationType type) {
    return type == RouteOperationType.CREATE || type == RouteOperationType.RETURN;
  }

  /** 已配置为随运营需求调度的出入库服务。 */
  public static boolean enabled(Route route) {
    return route != null && supports(route.operationType()) && read(route.metadata());
  }
}
