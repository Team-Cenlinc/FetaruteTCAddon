package org.fetarute.fetaruteTCAddon.drive.menu;

import java.util.Map;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.drive.setup.PowerSupply;

/**
 * 停车后菜单的布局：哪个槽位放哪个按钮，以及按钮在各状态下用哪张贴图。
 *
 * <p>菜单三排共 27 格。第一排：前三格是换向手柄（前进、空挡、后退），第五、六格是左右车门，左右以驾驶员面朝的方向为准。第二排是启动流程：
 * 钥匙、受电（受电弓、集电靴或发动机）、主断路器、辅助电源；simulation 级接着是压缩机、停放制动、制动试验与门旁路，standard 级最后一格是一键启动按钮。
 *
 * <p>第三排的第三至第六格是 simulation 级的表计与故障显示：主风缸表、制动缸表、制动管表（仅机车）与故障指示，只显示不操作。standard 级的菜单同样是 27 格，这几格空着。
 *
 * <p>本类不依赖服务器对象，便于单测。
 */
public final class MenuLayout {

  /** 菜单的槽位数。 */
  public static final int SIZE = 27;

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
          Map.entry(16, MenuAction.DOOR_BYPASS),
          Map.entry(17, MenuAction.START));

  private static final Map<Integer, MenuIndicator> INDICATORS =
      Map.ofEntries(
          Map.entry(20, MenuIndicator.MAIN_RESERVOIR),
          Map.entry(21, MenuIndicator.BRAKE_CYLINDER),
          Map.entry(22, MenuIndicator.BRAKE_PIPE),
          Map.entry(23, MenuIndicator.FAULTS));

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

  /** 槽位上的表计或指示；不是表计的槽位为空。 */
  public static Optional<MenuIndicator> indicatorAt(int slot) {
    return Optional.ofNullable(INDICATORS.get(slot));
  }

  /** 表计或指示所在的槽位。 */
  public static int indicatorSlot(MenuIndicator indicator) {
    for (Map.Entry<Integer, MenuIndicator> entry : INDICATORS.entrySet()) {
      if (entry.getValue() == indicator) {
        return entry.getKey();
      }
    }
    throw new IllegalArgumentException("菜单里没有这个表计: " + indicator);
  }

  /**
   * 按钮的贴图键，可带故障状态：有故障时用 {@code <名称>_fault}（如主断跳闸、压缩机故障）。
   *
   * @param fault 按钮对应的设备是否故障
   */
  public static String modelKey(
      MenuAction action, boolean active, boolean fault, PowerSupply supply) {
    String key = modelKey(action, active, supply);
    if (!fault) {
      return key;
    }
    if (key.endsWith("_on")) {
      key = key.substring(0, key.length() - "_on".length());
    } else if (key.endsWith("_off")) {
      key = key.substring(0, key.length() - "_off".length());
    }
    return key + "_fault";
  }

  /**
   * 按钮的贴图键（{@code fetarute:drive/} 之后的部分）。
   *
   * @param action 按钮
   * @param active 换向手柄：是否选中；车门：是否打开；启动流程开关：是否已接通；启动按钮：列车是否已启动；压缩机：是否运转（机车为开关是否打开）；
   *     停放制动：是否已缓解；制动试验：是否已通过；门旁路：是否旁路
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
          case DOOR_BYPASS -> "door_bypass" + onOff;
        };
  }

  /** 受电开关的贴图名：受电弓按接触网高度区分，集电靴与发动机各有一张。 */
  static String powerKey(PowerSupply supply) {
    return supply == PowerSupply.DIESEL ? "engine" : supply.key();
  }
}
