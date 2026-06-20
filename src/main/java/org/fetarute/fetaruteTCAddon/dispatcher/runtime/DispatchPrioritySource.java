package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

/**
 * 运行时调度优先级的来源。
 *
 * <p>该枚举只描述 {@link DispatchPriorityResolver} 的解析路径，方便测试服日志确认同一列车在 periodic tick 与 event-driven
 * signal re-evaluation 中是否使用了同一个 priority 语义。
 */
public enum DispatchPrioritySource {

  /** 人工写入的 {@code FTA_PRIORITY}，优先级最高。 */
  MANUAL_FTA_PRIORITY,

  /** 运行时推进表中保存的 route UUID。 */
  ROUTE_PROGRESS_UUID,

  /** TrainProperties 上的 {@code FTA_ROUTE_ID}。 */
  FTA_ROUTE_ID,

  /** 当前 {@code RouteDefinition.id()} 或其 metadata。 */
  ROUTE_DEFINITION_ID,

  /** TrainProperties 上的 operator/line/route code tags。 */
  ROUTE_CODE_TAGS,

  /** 未能识别 route，按安全默认值处理。 */
  DEFAULT_UNKNOWN
}
