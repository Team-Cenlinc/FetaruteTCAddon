package org.fetarute.fetaruteTCAddon.drive.menu;

import java.util.Map;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.drive.setup.PowerSupply;

/**
 * 停车后菜单的布局：哪个槽位放哪个按钮，以及按钮在各状态下用哪张贴图。
 *
 * <p>菜单两排共 18 格。第一排：前三格是换向手柄（前进、空挡、后退），第五、六格是左右车门，左右以驾驶员面朝的方向为准。第二排是启动流程：
 * 钥匙、受电（受电弓、集电靴或发动机）、主断路器、辅助电源；simulation 级接着是压缩机、停放制动与制动试验，standard 级最后一格是一键启动按钮。
 *
 * <p>本类不依赖服务器对象，便于单测。
 */
public final class MenuLayout {

  /** 菜单的槽位数。 */
  public static final int SIZE = 18;

  /** 材质包里模型键的前缀（命名空间之后）。 */
  public static final String MODEL_PREFIX = "panel/";

  private static final Map<Integer, MenuAction> ACTIONS =
      Map.ofEntries(
          Map.entry(0, MenuAction.REVERSER_FORWARD),
          Map.entry(1, MenuAction.REVERSER_NEUTRAL),
          Map.entry(2, MenuAction.REVERSER_REVERSE),
          Map.entry(4, MenuAction.DOOR_LEFT),
          Map.entry(5, MenuAction.DOOR_RIGHT),
          Map.entry(9, MenuAction.KEY),
          Map.entry(10, MenuAction.POWER),
          Map.entry(11, MenuAction.BREAKER),
          Map.entry(12, MenuAction.AUX),
          Map.entry(13, MenuAction.COMPRESSOR),
          Map.entry(14, MenuAction.PARKING_BRAKE),
          Map.entry(15, MenuAction.BRAKE_TEST),
          Map.entry(17, MenuAction.START));

  private MenuLayout() {}

  /** 槽位上的操作；空槽位为空。 */
  public static Optional<MenuAction> actionAt(int slot) {
    return Optional.ofNullable(ACTIONS.get(slot));
  }

  /** 操作所在的槽位。 */
  public static int slotOf(MenuAction action) {
    for (Map.Entry<Integer, MenuAction> entry : ACTIONS.entrySet()) {
      if (entry.getValue() == action) {
        return entry.getKey();
      }
    }
    throw new IllegalArgumentException("菜单里没有这个操作: " + action);
  }

  /**
   * 按钮的贴图键（{@code fetarute:drive/} 之后的部分）。
   *
   * @param action 按钮
   * @param active 换向手柄：是否选中；车门：是否打开；启动流程开关：是否已接通；启动按钮：列车是否已启动；压缩机：是否运转（机车为开关是否打开）；
   *     停放制动：是否已缓解；制动试验：是否已通过
   * @param supply 列车的受电方式，决定受电开关用哪张贴图
   */
  public static String modelKey(MenuAction action, boolean active, PowerSupply supply) {
    String onOff = active ? "_on" : "_off";
    return MODEL_PREFIX
        + switch (action) {
          case REVERSER_FORWARD -> active ? "reverser_f_sel" : "reverser_f";
          case REVERSER_NEUTRAL -> active ? "reverser_n_sel" : "reverser_n";
          case REVERSER_REVERSE -> active ? "reverser_r_sel" : "reverser_r";
          case DOOR_LEFT -> "door_l" + onOff;
          case DOOR_RIGHT -> "door_r" + onOff;
          case KEY -> "key" + onOff;
          case POWER -> powerKey(supply) + onOff;
          case BREAKER -> "breaker" + onOff;
          case AUX -> "aux" + onOff;
          case START -> "start" + onOff;
          case COMPRESSOR -> "compressor" + onOff;
          case PARKING_BRAKE -> "release" + onOff;
          case BRAKE_TEST -> "test" + onOff;
        };
  }

  /** 受电开关的贴图名：受电弓按接触网高度区分，集电靴与发动机各有一张。 */
  static String powerKey(PowerSupply supply) {
    return supply == PowerSupply.DIESEL ? "engine" : supply.key();
  }
}
