package org.fetarute.fetaruteTCAddon.dispatcher.runtime.coupling;

import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * 运行时调度里与拆分/联挂有关的状态与入口：插件自己发起的操作登记，以及操作之后要求调度按现场车身重新认列车。
 *
 * <p>由运行时调度服务在构造时建立，私有能力用方法引用接进来（调度服务的方法数贴着静态检查的上限，不为这里另开方法）。
 */
public final class CouplingRuntime {

  private final CouplingOperations operations = new CouplingOperations();
  private final Consumer<String> physicalRehydration;
  private volatile BiConsumer<String, String> renameListener = (from, to) -> {};

  /**
   * @param physicalRehydration 忘掉这列车已认定的物理车身，下一次信号检查按现场车身重新占用
   */
  public CouplingRuntime(Consumer<String> physicalRehydration) {
    this.physicalRehydration = Objects.requireNonNull(physicalRehydration, "physicalRehydration");
  }

  /** 插件自己发起的拆分/联挂登记。 */
  public CouplingOperations operations() {
    return operations;
  }

  /**
   * 拆分、联挂之后车身变了：让调度在这列车下一次信号检查时按现场车身重新占用（保留其它列车的占用，现场重叠照样记下）。
   *
   * @param trainName 逻辑车名
   */
  public void requestPhysicalRehydration(String trainName) {
    if (trainName != null && !trainName.isBlank()) {
      physicalRehydration.accept(trainName);
    }
  }

  /**
   * 接收调度改名（待命复用接下一趟等）的通知：拆挂服务据此把同股道列车对立即迁到新名下。改名与新一趟的授权在同一拍，等定时维护再迁， 这一拍新名对不上豁免，授权失败丢掉共用的资源。 传入
   * {@code null} 恢复为不通知。
   */
  public void setRenameListener(BiConsumer<String, String> listener) {
    this.renameListener = listener == null ? (from, to) -> {} : listener;
  }

  /**
   * 调度改了列车的逻辑车名（占用账本已迁到新名）。监听方出错不影响改名。
   *
   * @param from 旧名
   * @param to 新名
   */
  public void renamed(String from, String to) {
    if (from == null || to == null || from.equals(to)) {
      return;
    }
    try {
      renameListener.accept(from, to);
    } catch (RuntimeException ignored) {
      // 列车对迁不过去时由定时维护按标签兜底
    }
  }
}
