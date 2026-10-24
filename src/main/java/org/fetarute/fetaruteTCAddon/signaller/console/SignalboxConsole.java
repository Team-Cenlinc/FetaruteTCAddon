package org.fetarute.fetaruteTCAddon.signaller.console;

import java.util.Optional;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * 控制台（{@code signalbox-*} 布局的站台屏）与信号员模块之间的接缝：站台屏每秒最多取一次一帧数据，玩家点击时把点到的东西交回来。
 *
 * <p>控制台按“运营商 + 控制区代码”认信号楼：屏幕绑了控制区才是控制台。只在服务器主线程调用，实现必须便宜、不抛异常。
 */
public interface SignalboxConsole {

  /** 信号员模块未启用：没有控制台数据，点击无效。 */
  SignalboxConsole NONE =
      new SignalboxConsole() {
        @Override
        public Optional<SignalboxState> state(String operator, String zone) {
          return Optional.empty();
        }

        @Override
        public void press(
            Player player, Location console, String operator, String zone, ConsoleTarget target) {}
      };

  /**
   * 控制区此刻的控制台数据。
   *
   * @param operator 运营商代码（不分大小写）
   * @param zone 控制区代码（不分大小写）
   * @return 控制区不存在或模块未就绪时为空
   */
  Optional<SignalboxState> state(String operator, String zone);

  /**
   * 玩家在控制台上点了一下（右键；左键一律交 {@link ConsoleTarget.Deselect}）。还没上岗的玩家点任何地方都是请求上岗。
   *
   * @param player 点击的玩家
   * @param console 被点的屏幕的中心（上岗后据此判断信号员有没有离开控制台）
   * @param operator 运营商代码
   * @param zone 控制区代码
   * @param target 点到的东西
   */
  void press(Player player, Location console, String operator, String zone, ConsoleTarget target);
}
