package org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy;

/** 冲突清空证据的可信层级。 */
public enum ConflictClearingEvidenceKind {
  /** 仅证明当前路径拓扑上看起来会离开单线冲突区，只能用于诊断。 */
  TOPOLOGY_EXIT_HINT(false),

  /** 占用层已基于 blocker、队列和 release lock 认证过的冲突释放。 */
  VERIFIED_CONFLICT_RELEASE(true),

  /** 外部 drain authority 注册表认证过的清空授权。 */
  VERIFIED_DRAIN_AUTHORITY(true);

  private final boolean releaseVerified;

  ConflictClearingEvidenceKind(boolean releaseVerified) {
    this.releaseVerified = releaseVerified;
  }

  /** 是否足以作为冲突释放判定的 verified release hint。 */
  public boolean releaseVerified() {
    return releaseVerified;
  }
}
