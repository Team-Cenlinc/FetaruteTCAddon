package org.fetarute.fetaruteTCAddon.signaller.console;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.fetarute.fetaruteTCAddon.display.pids.yard.YardArea;

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

  /**
   * 同 {@link #press(Player, Location, String, String,
   * ConsoleTarget)}，带上屏幕身份：中心工位上有人时点的是在岗那位的区，空着时大区有人值守就请求分一片。
   *
   * @param screen 被点的屏幕
   * @param workstation 这块屏是中心工位
   */
  default void press(
      Player player,
      Location console,
      UUID screen,
      boolean workstation,
      String operator,
      String zone,
      ConsoleTarget target) {
    press(player, console, operator, zone, target);
  }

  /**
   * 这块屏此刻该画哪个区：中心工位上有人值守时画在岗那位的区（临时小区），否则画绑定的区。
   *
   * @param screen 屏幕
   * @param operator 运营商代码
   * @param zone 屏幕绑定的控制区代码
   */
  default String zoneFor(UUID screen, String operator, String zone) {
    return zone;
  }

  /**
   * 中心工位分出去的临时小区（不在控制区目录里，站场图按它的成员画）。
   *
   * @return 不是临时小区时为空
   */
  default Optional<ConsoleZone> transientZone(String operator, String code) {
    return Optional.empty();
  }

  /**
   * 站场图要画的一片：名字与成员。
   *
   * @param name 名字（标题）
   * @param subtitle 副标题（中心工位写所辖车站，太多时写“等 N 站”）；没有时为空串
   * @param members 成员车站与车库
   */
  record ConsoleZone(String name, String subtitle, List<YardArea.Member> members) {
    public ConsoleZone {
      subtitle = subtitle == null ? "" : subtitle;
      members = List.copyOf(members);
    }
  }
}
