package org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy;

/**
 * 已写入占用层的 claim 角色。
 *
 * <p>ClaimRole 从 ResourceIntent 派生，用于后续 blocker 分类与诊断。它不会改变资源 key 本身的互斥模型，只说明该 claim
 * 是前向授权、保护保留还是队列/hold 语义。
 */
public enum ClaimRole {
  MOVEMENT_REQUIRED,
  PROTECTIVE_RETAIN,
  QUEUE_POSITION,
  HOLD_ONLY,
  LOOKAHEAD_PREVIEW;

  public static ClaimRole fromIntent(ResourceIntent intent) {
    if (intent == null) {
      return MOVEMENT_REQUIRED;
    }
    return switch (intent) {
      case MOVEMENT_REQUIRED -> MOVEMENT_REQUIRED;
      case PROTECTIVE_RETAIN -> PROTECTIVE_RETAIN;
      case QUEUE_POSITION -> QUEUE_POSITION;
      case HOLD_ONLY -> HOLD_ONLY;
      case LOOKAHEAD_PREVIEW -> LOOKAHEAD_PREVIEW;
    };
  }
}
