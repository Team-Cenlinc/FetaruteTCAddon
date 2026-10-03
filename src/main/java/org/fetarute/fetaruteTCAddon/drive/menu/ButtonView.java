package org.fetarute.fetaruteTCAddon.drive.menu;

import java.util.Map;
import java.util.Objects;
import org.fetarute.fetaruteTCAddon.drive.setup.PowerSupply;

/**
 * 菜单上一个按钮此刻的样子。
 *
 * @param action 按钮
 * @param active 换向手柄：是否选中；车门：是否打开；启动流程开关：是否已接通；启动按钮：列车是否已启动；压缩机：是否运转；停放制动：是否已缓解； 制动试验：是否已通过
 * @param busy 启动流程开关、启动按钮或制动试验：是否正在进行
 * @param supply 列车的受电方式
 * @param remainingSeconds 正在接通时的剩余秒数；不在接通时为 -1
 * @param clickable 能否点击；standard 级的系统开关与动车组的压缩机只作指示灯
 * @param detailKey 额外一行说明（如风压读数）的语言键；没有时为 {@code null}
 * @param detailValues 说明里的占位符
 */
public record ButtonView(
    MenuAction action,
    boolean active,
    boolean busy,
    PowerSupply supply,
    long remainingSeconds,
    boolean clickable,
    String detailKey,
    Map<String, String> detailValues) {

  public ButtonView {
    Objects.requireNonNull(action, "action");
    Objects.requireNonNull(supply, "supply");
    detailValues = detailValues == null ? Map.of() : Map.copyOf(detailValues);
  }

  /** 没有额外说明的按钮。 */
  public ButtonView(
      MenuAction action,
      boolean active,
      boolean busy,
      PowerSupply supply,
      long remainingSeconds,
      boolean clickable) {
    this(action, active, busy, supply, remainingSeconds, clickable, null, Map.of());
  }

  /** 换向手柄或车门按钮。 */
  public static ButtonView simple(MenuAction action, boolean active) {
    return new ButtonView(action, active, false, PowerSupply.PTG5, -1, true);
  }
}
