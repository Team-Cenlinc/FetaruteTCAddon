package org.fetarute.fetaruteTCAddon.dispatcher.runtime.coupling;

import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 插件自己发起的拆分/联挂：调用 TrainCarts 期间登记涉及的编组，运行时监听器据此不把这次拓扑变化当成事故（不隔离、不销毁、不触发全网先停车）。
 *
 * <p>TrainCarts 的拆分与联挂都是同步调用，成员移除、编组移除与联挂事件都在调用里发出，所以登记只在调用期间有效：{@link #begin} 返回的范围关闭即撤销， 不存在跨
 * tick 的悬挂登记。编组按对象身份认（同名的拆分残编不会被误认）。只在服务器主线程使用。
 */
public final class CouplingOperations {

  private final Set<Object> active = Collections.newSetFromMap(new IdentityHashMap<>());

  /**
   * 登记这一次操作涉及的编组。
   *
   * @param groups 涉及的编组（拆分时是源编组，联挂时是两侧编组）；{@code null} 元素忽略
   * @return 关闭即撤销登记的范围，配合 try-with-resources 使用
   */
  public Scope begin(Collection<?> groups) {
    Objects.requireNonNull(groups, "groups");
    List<Object> added = new java.util.ArrayList<>();
    for (Object group : groups) {
      if (group != null && active.add(group)) {
        added.add(group);
      }
    }
    return new Scope(added);
  }

  /** 这个编组是否正处在插件自己发起的拆分/联挂里。 */
  public boolean involves(Object group) {
    return group != null && active.contains(group);
  }

  /** 这些编组里是否有任何一个正处在插件自己发起的拆分/联挂里。 */
  public boolean involvesAny(Object... groups) {
    if (groups == null) {
      return false;
    }
    for (Object group : groups) {
      if (involves(group)) {
        return true;
      }
    }
    return false;
  }

  /** 此刻是否正在一次插件自己发起的拆分/联挂调用里。调用里 TrainCarts 同步发出的站牌事件都来自这次拓扑变化（拆下的一半此刻还带着 TrainCarts 的临时车名）。 */
  public boolean inProgress() {
    return !active.isEmpty();
  }

  /** 一次登记的范围：关闭时只撤销这一次加进来的编组。 */
  public final class Scope implements AutoCloseable {

    private final List<Object> groups;
    private boolean closed;

    private Scope(List<Object> groups) {
      this.groups = groups;
    }

    @Override
    public void close() {
      if (closed) {
        return;
      }
      closed = true;
      for (Object group : groups) {
        active.remove(group);
      }
    }
  }
}
