package org.fetarute.fetaruteTCAddon.drive.dynamics;

import java.util.Objects;

/**
 * 把快捷栏选中的槽位换算成档位，并执行紧急制动锁定。
 *
 * <p>滚轮从 {@code EB}（槽位 9）再滚一格会回绕到 {@code P3}（槽位 1），数字键 {@code 1} 产生的选中变化与之完全相同，无法区分。
 * 所以从规则上防止一步从紧急制动跳到满牵引：
 *
 * <ul>
 *   <li>处于 {@code EB} 时，选中牵引档一律忽略，档位改为 {@code N}；
 *   <li>未停稳的 {@code EB} 保持不变，选中槽位拨回 {@code EB}（紧急制动只能停稳后缓解）；
 *   <li>离开 {@code EB} 只能先到 {@code N} 或制动档。
 * </ul>
 *
 * <p>本类不依赖任何服务器对象，只保存当前档位。
 */
public final class NotchSelector {

  /**
   * 一次选择的结果。
   *
   * @param notch 选择之后的档位
   * @param correctedSlot 快捷栏应当显示的槽位；与玩家刚选的槽位不同就要把选中槽位拨过去
   * @param changed 档位是否发生变化
   */
  public record Selection(Notch notch, int correctedSlot, boolean changed) {
    public Selection {
      Objects.requireNonNull(notch, "notch");
    }
  }

  private Notch current = Notch.N;

  public Notch current() {
    return current;
  }

  /** 直接设定档位（会话开始、保护介入时使用），不经过锁定规则。 */
  public void force(Notch notch) {
    this.current = Objects.requireNonNull(notch, "notch");
  }

  /**
   * 处理玩家选中了某个槽位。
   *
   * @param slot 玩家选中的快捷栏槽位
   * @param stopped 列车是否已停稳
   * @return 选择结果；槽位不在 0–8 内时维持当前档位
   */
  public Selection select(int slot, boolean stopped) {
    Notch requested = Notch.fromSlot(slot).orElse(null);
    if (requested == null) {
      return new Selection(current, current.slot(), false);
    }
    if (requested == current) {
      return new Selection(current, current.slot(), false);
    }
    if (current == Notch.EB) {
      if (!stopped) {
        return new Selection(Notch.EB, Notch.EB.slot(), false);
      }
      if (requested.isTraction()) {
        current = Notch.N;
        return new Selection(Notch.N, Notch.N.slot(), true);
      }
    }
    current = requested;
    return new Selection(requested, requested.slot(), true);
  }
}
