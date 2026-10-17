package org.fetarute.fetaruteTCAddon.display.pids.yard;

import java.util.UUID;
import org.fetarute.fetaruteTCAddon.display.pids.PidsStationKey;
import org.fetarute.fetaruteTCAddon.display.pids.layout.PidsLayout;

/** 站场图的数据来源：在主线程按站台屏的检查间隔调用。 */
@FunctionalInterface
public interface PidsYardSource {

  /** 未接入调度：一律显示“调度图未就绪”。 */
  PidsYardSource NONE =
      (station, worldId, widget) -> YardPicture.unavailable(YardPicture.Problem.GRAPH_NOT_READY);

  /**
   * 车站此刻的站场图。
   *
   * @param station 车站
   * @param worldId 屏幕所在世界
   * @param widget 站场图组件（决定画在哪里）
   */
  YardPicture picture(PidsStationKey station, UUID worldId, PidsLayout.Yard widget);
}
