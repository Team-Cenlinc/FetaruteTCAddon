package org.fetarute.fetaruteTCAddon.dispatcher.runtime.supervisor;

/** 智能调度层输出的确定性动作。 */
public enum DispatchAction {
  PROCEED(DispatchEffectClass.SIGNAL_ADVISORY),
  PROCEED_WITH_CAUTION(DispatchEffectClass.SIGNAL_ADVISORY),
  CAUTION_SPEED_LIMIT(DispatchEffectClass.SIGNAL_ADVISORY),
  HOLD_AT_SIGNAL(DispatchEffectClass.SIGNAL_CONSTRAINT),
  WAIT_FOR_PRIORITY_TRAIN(DispatchEffectClass.SIGNAL_CONSTRAINT),
  ALLOW_DRAIN_THROUGH(DispatchEffectClass.AUTHORITY_PRECHECK),
  RELEASE_STALE_RETAIN(DispatchEffectClass.OCCUPANCY_MUTATION),
  RELEASE_STALE_QUEUE_ENTRY(DispatchEffectClass.OCCUPANCY_MUTATION),
  RELEASE_STALE_SWITCHER_CLAIM(DispatchEffectClass.OCCUPANCY_MUTATION),
  FORWARD_UNLOCK(DispatchEffectClass.OCCUPANCY_MUTATION),
  RECALCULATE_AUTHORITY(DispatchEffectClass.AUTHORITY_PRECHECK),
  DESTROY_CANDIDATE(DispatchEffectClass.DESTROY_ACTION),
  NO_ACTION(DispatchEffectClass.DIAGNOSTIC_ONLY);

  private final DispatchEffectClass effectClass;

  DispatchAction(DispatchEffectClass effectClass) {
    this.effectClass = effectClass == null ? DispatchEffectClass.DIAGNOSTIC_ONLY : effectClass;
  }

  /** 该动作默认归属的副作用等级。 */
  public DispatchEffectClass effectClass() {
    return effectClass;
  }
}
