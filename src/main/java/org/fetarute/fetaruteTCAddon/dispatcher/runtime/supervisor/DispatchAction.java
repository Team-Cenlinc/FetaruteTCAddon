package org.fetarute.fetaruteTCAddon.dispatcher.runtime.supervisor;

/** 智能调度层输出的确定性动作。 */
public enum DispatchAction {
  NONE(DispatchEffectClass.DIAGNOSTIC_ONLY, false, false),
  OBSERVE_ONLY_LOG(DispatchEffectClass.DIAGNOSTIC_ONLY, false, false),
  RELEASE_SELF_OWNED_STALE_PROTECTIVE_RETAIN(DispatchEffectClass.OCCUPANCY_MUTATION, true, false),
  ACQUIRE_SPECULATIVE_UNLOCK_RESERVATION(DispatchEffectClass.OCCUPANCY_MUTATION, true, false),
  RELEASE_SPECULATIVE_UNLOCK_RESERVATION(DispatchEffectClass.OCCUPANCY_MUTATION, true, false),
  REQUEST_UNLOCK_AUTHORITY_REEVALUATION(
      DispatchEffectClass.SIGNAL_REEVALUATION_REQUEST, true, false),
  SMART_HEAD_ON_YIELD(DispatchEffectClass.OCCUPANCY_MUTATION, true, false),
  ACQUIRE_VERIFIED_SWITCHER_DRAIN_AUTHORITY(DispatchEffectClass.OCCUPANCY_MUTATION, true, false),
  /** 已验证 admission 拒绝后的本地保持；不写 destination、token 或 occupancy。 */
  SMART_ADMISSION_HOLD(DispatchEffectClass.SIGNAL_CONSTRAINT, true, false),
  /** HealthMonitor 的信号刷新、硬停或受限重启；不得直接签发新的 Movement Authority。 */
  SMART_HEALTH_SIGNAL_RECOVERY(DispatchEffectClass.SIGNAL_CONSTRAINT, true, false),
  SMART_DRAIN_UNLOCK_SIGNAL_ADVISORY(DispatchEffectClass.SIGNAL_CONSTRAINT, true, false),
  SMART_FORWARD_UNLOCK_SIGNAL_ADVISORY(DispatchEffectClass.SIGNAL_CONSTRAINT, true, false),
  /** 仅在完整 deadlock review 已通过后销毁已选定的 leader；不可作为普通恢复动作使用。 */
  EXECUTE_VERIFIED_DEADLOCK_DESTROY(DispatchEffectClass.DESTROY_ACTION, true, false),
  /** 仅在长期无进展、恢复耗尽且不属于正常排队后执行通用 stuck cleanup。 */
  EXECUTE_VERIFIED_STUCK_CLEANUP(DispatchEffectClass.DESTROY_ACTION, true, false),
  SAME_DIRECTION_FOLLOW_THROUGH_PREVIEW(DispatchEffectClass.DIAGNOSTIC_ONLY, false, false),
  FORCE_PROCEED(DispatchEffectClass.SIGNAL_CONSTRAINT, true, true),
  DESTROY_TRAIN(DispatchEffectClass.DESTROY_ACTION, true, true),
  CLEAR_EXTERNAL_OCCUPANCY(DispatchEffectClass.OCCUPANCY_MUTATION, true, true),
  CLEAR_DESTINATION(DispatchEffectClass.AUTHORITY_PRECHECK, true, true),
  INVALIDATE_MOVEMENT_TOKEN(DispatchEffectClass.AUTHORITY_PRECHECK, true, true),
  ALLOW_OPPOSITE_DIRECTION_BYPASS(DispatchEffectClass.AUTHORITY_PRECHECK, true, true),
  ALLOW_TURNBACK_BYPASS(DispatchEffectClass.AUTHORITY_PRECHECK, true, true),
  SAME_DIRECTION_FOLLOW_THROUGH_ALLOW(DispatchEffectClass.SIGNAL_CONSTRAINT, true, true),

  /** 兼容前方信号决策语义；真实 occupancy mutation 不得从该组动作直接发起。 */
  PROCEED(DispatchEffectClass.SIGNAL_ADVISORY),
  PROCEED_WITH_CAUTION(DispatchEffectClass.SIGNAL_ADVISORY, true, false),
  CAUTION_SPEED_LIMIT(DispatchEffectClass.SIGNAL_ADVISORY, true, false),
  HOLD_AT_SIGNAL(DispatchEffectClass.SIGNAL_CONSTRAINT),
  WAIT_FOR_PRIORITY_TRAIN(DispatchEffectClass.SIGNAL_CONSTRAINT),
  ALLOW_DRAIN_THROUGH(DispatchEffectClass.AUTHORITY_PRECHECK),
  NO_ACTION(DispatchEffectClass.DIAGNOSTIC_ONLY, false, false);

  private final DispatchEffectClass effectClass;
  private final boolean dispatcherMutation;
  private final boolean forbiddenDispatcherAction;

  DispatchAction(DispatchEffectClass effectClass) {
    this(effectClass, false, false);
  }

  DispatchAction(
      DispatchEffectClass effectClass,
      boolean dispatcherMutation,
      boolean forbiddenDispatcherAction) {
    this.effectClass = effectClass == null ? DispatchEffectClass.DIAGNOSTIC_ONLY : effectClass;
    this.dispatcherMutation = dispatcherMutation;
    this.forbiddenDispatcherAction = forbiddenDispatcherAction;
  }

  /** 该动作默认归属的副作用等级。 */
  public DispatchEffectClass effectClass() {
    return effectClass;
  }

  /** 是否是 Dispatcher effect layer 中登记过的真实 mutation action。 */
  public boolean dispatcherMutation() {
    return dispatcherMutation;
  }

  /** 本轮收敛策略禁止由 Smart Dispatcher action path 执行的动作。 */
  public boolean forbiddenDispatcherAction() {
    return forbiddenDispatcherAction;
  }

  /** 该 action 是否允许进入 Smart Dispatcher effect layer。 */
  public boolean executableDispatcherAction() {
    return dispatcherMutation && !forbiddenDispatcherAction;
  }
}
