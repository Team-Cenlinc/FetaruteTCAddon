package org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy;

import java.util.Objects;

/**
 * 冲突区清空放行的证据。
 *
 * <p>该证据必须由运行时结合当前位置、已持有 claim 与前方路径计算得出。单纯把 purpose 标成 {@link
 * AuthorizationPurpose#CONFLICT_CLEARING} 不足以触发 release，避免 departure/spawn 把“准备进入冲突区”误判为“正在清空出口”。
 *
 * @param conflictKey 冲突资源 key
 * @param trainAlreadyInsideSameConflict 列车是否已经持有同一个冲突区
 * @param targetIsExitFromConflict 当前授权目标是否离开该冲突区
 * @param kind 证据可信层级
 * @param source 证据来源
 */
public record ConflictReleaseHint(
    String conflictKey,
    boolean trainAlreadyInsideSameConflict,
    boolean targetIsExitFromConflict,
    ConflictClearingEvidenceKind kind,
    String source) {

  public ConflictReleaseHint {
    Objects.requireNonNull(conflictKey, "conflictKey");
    conflictKey = conflictKey.trim();
    if (conflictKey.isEmpty()) {
      throw new IllegalArgumentException("conflictKey 不能为空");
    }
    kind = kind == null ? ConflictClearingEvidenceKind.TOPOLOGY_EXIT_HINT : kind;
    source = source == null || source.isBlank() ? "-" : source.trim();
  }

  public ConflictReleaseHint(
      String conflictKey,
      boolean trainAlreadyInsideSameConflict,
      boolean targetIsExitFromConflict) {
    this(
        conflictKey,
        trainAlreadyInsideSameConflict,
        targetIsExitFromConflict,
        trainAlreadyInsideSameConflict && targetIsExitFromConflict
            ? ConflictClearingEvidenceKind.VERIFIED_CONFLICT_RELEASE
            : ConflictClearingEvidenceKind.TOPOLOGY_EXIT_HINT,
        "legacy");
  }

  /** 返回该 hint 是否能证明指定冲突区可执行清空放行。 */
  public boolean verifiedFor(String key) {
    return key != null
        && conflictKey.equals(key.trim())
        && trainAlreadyInsideSameConflict
        && targetIsExitFromConflict
        && kind.releaseVerified();
  }

  /** 创建一条已验证的清空放行证据。 */
  public static ConflictReleaseHint verified(String conflictKey) {
    return new ConflictReleaseHint(
        conflictKey,
        true,
        true,
        ConflictClearingEvidenceKind.VERIFIED_CONFLICT_RELEASE,
        "occupancy-release");
  }

  /** 创建一条仅供诊断的拓扑出口提示。 */
  public static ConflictReleaseHint topologyExit(String conflictKey, String source) {
    return new ConflictReleaseHint(
        conflictKey, true, true, ConflictClearingEvidenceKind.TOPOLOGY_EXIT_HINT, source);
  }

  /** 创建一条由 drain authority 注册表认证过的清空授权。 */
  public static ConflictReleaseHint verifiedDrainAuthority(String conflictKey, String source) {
    return new ConflictReleaseHint(
        conflictKey, true, true, ConflictClearingEvidenceKind.VERIFIED_DRAIN_AUTHORITY, source);
  }
}
