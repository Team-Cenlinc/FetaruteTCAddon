package org.fetarute.fetaruteTCAddon.display;

import com.bergerkiller.bukkit.tc.controller.MinecartGroup;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.display.hud.TrainHudContext;
import org.fetarute.fetaruteTCAddon.display.hud.trip.TripDialogService;

/**
 * 展示层服务入口：负责启动/停止 HUD、站牌等 UI 组件。
 *
 * <p>注意：该层只做“展示”，不改变调度状态机。
 */
public interface DisplayService {

  /** 启动展示层（注册监听/启动刷新任务）。 */
  void start();

  /** 停止展示层（取消任务并释放资源）。 */
  void stop();

  /** 列车的车内 HUD 上下文（下一站、终点等，与乘客看到的一致）；不是 FTA 管控列车或展示层未启用时为空。 */
  default Optional<TrainHudContext> hudContext(MinecartGroup group) {
    return Optional.empty();
  }

  /** 后续站点对话框；展示层未启用时为空。 */
  default Optional<TripDialogService> tripDialog() {
    return Optional.empty();
  }
}
