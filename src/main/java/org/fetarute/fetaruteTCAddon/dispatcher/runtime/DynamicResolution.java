package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * DYNAMIC 目标解析结果。
 *
 * <p>{@link State#NOT_APPLICABLE} 表示当前授权窗口内没有需要 materialize 的 DYNAMIC stop，调用方可以继续使用普通 route
 * 节点；{@link State#SELECTED} 表示已经选出具体候选，但不代表完成进路授权；{@link State#BLOCKED} 表示确有 DYNAMIC
 * stop，但当前无法形成安全目标，调用方必须保持停车且不得退回声明节点申请前方进路。
 *
 * @param state 解析状态
 * @param selected 已选出的具体结果，仅 SELECTED 时存在
 * @param reason 稳定诊断原因
 * @param blockedStopIndex 已知的实际受阻 stop 索引；提前选台时可能隔着中间 PASS，仅用于等待通知
 * @param <T> 具体选择结果类型
 */
record DynamicResolution<T>(
    State state, Optional<T> selected, String reason, OptionalInt blockedStopIndex) {

  DynamicResolution {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(selected, "selected");
    Objects.requireNonNull(blockedStopIndex, "blockedStopIndex");
    reason =
        reason == null || reason.isBlank() ? state.name().toLowerCase(Locale.ROOT) : reason.trim();
    if ((state == State.SELECTED) != selected.isPresent()) {
      throw new IllegalArgumentException("SELECTED 状态必须且只能携带一个具体结果");
    }
    if (blockedStopIndex.isPresent()
        && (state != State.BLOCKED || blockedStopIndex.getAsInt() < 0)) {
      throw new IllegalArgumentException("受阻索引只能由 BLOCKED 携带且必须非负");
    }
  }

  static <T> DynamicResolution<T> notApplicable(String reason) {
    return new DynamicResolution<>(
        State.NOT_APPLICABLE, Optional.empty(), reason, OptionalInt.empty());
  }

  static <T> DynamicResolution<T> selected(T value) {
    return new DynamicResolution<>(
        State.SELECTED,
        Optional.of(Objects.requireNonNull(value, "value")),
        "selected",
        OptionalInt.empty());
  }

  static <T> DynamicResolution<T> blocked(String reason) {
    return blocked(reason, OptionalInt.empty());
  }

  /** 保留选台器已确认的受阻目标，供上层建立精确容量通知，不作为授权输入。 */
  static <T> DynamicResolution<T> blocked(String reason, OptionalInt stopIndex) {
    return new DynamicResolution<>(State.BLOCKED, Optional.empty(), reason, stopIndex);
  }

  boolean isBlocked() {
    return state == State.BLOCKED;
  }

  boolean isSelected() {
    return state == State.SELECTED;
  }

  /** DYNAMIC 目标解析的三态结果。 */
  enum State {
    NOT_APPLICABLE,
    SELECTED,
    BLOCKED
  }
}
