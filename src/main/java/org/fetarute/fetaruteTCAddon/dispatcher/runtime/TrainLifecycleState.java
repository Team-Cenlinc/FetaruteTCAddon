package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

/**
 * 列车在调度系统中的生命周期状态。
 *
 * <h3>状态机概览</h3>
 *
 * <pre>
 * ┌──────────────────────────────────────────────────────────────────────────────┐
 * │                           列车生命周期状态机                                    │
 * └──────────────────────────────────────────────────────────────────────────────┘
 *
 *   [Depot Spawn]
 *        │
 *        ▼
 *   ┌─────────────┐    到达 TERM 节点     ┌─────────────────┐
 *   │ IN_SERVICE  │ ─────────────────────▶│   TERMINATING   │
 *   └─────────────┘                       └─────────────────┘
 *        ▲                                       │
 *        │                                       │ 完成停站流程
 *   发车成功                                      ▼
 *        │                                ┌─────────────────┐
 *   ┌─────────────┐    门已关/位置合法     │     LAYOVER     │
 *   │ DISPATCHING │ ◀────────────────────│ (等待关门完成)    │
 *   └─────────────┘                       └─────────────────┘
 *        ▲                                       │
 *        │                                       │ 关门完成
 *   分配新票据                                    ▼
 *        │                                ┌─────────────────┐
 *        └────────────────────────────────│  LAYOVER_READY  │
 *                                         │ (可接受调度)      │
 *                                         └─────────────────┘
 *                                                │
 *                                    ┌───────────┴───────────┐
 *                        分配 RETURN 票据│                     │超时/超限回收
 *                                    ▼                       ▼
 *                             ┌─────────────┐         ┌─────────────┐
 *                             │ DEADHEADING │         │  DESTROYED  │
 *                             │ (回库运行)   │────────▶│ (已销毁)     │
 *                             └─────────────┘  到达   └─────────────┘
 *                                             DSTY
 *
 *   [侧线缓冲场景]
 *
 *   ┌─────────────┐    站台占满      ┌─────────────────┐    站台空闲
 *   │ IN_SERVICE  │ ───────────────▶│  SIDING_PARKED  │ ──────────────▶ 回站台
 *   └─────────────┘                 │ (侧线等待)       │
 *                                   └─────────────────┘
 * </pre>
 *
 * <h3>状态说明</h3>
 *
 * <table border="1">
 * <tr><th>状态</th><th>说明</th><th>典型持续时间</th></tr>
 * <tr><td>IN_SERVICE</td><td>正常运营，执行 Route 任务</td><td>整个运营周期</td></tr>
 * <tr><td>TERMINATING</td><td>正在进入终到流程</td><td>数秒到数十秒</td></tr>
 * <tr><td>LAYOVER</td><td>终到站停靠中，等待关门完成</td><td>数秒</td></tr>
 * <tr><td>LAYOVER_READY</td><td>待命就绪，可接受新任务</td><td>数秒到数分钟</td></tr>
 * <tr><td>DISPATCHING</td><td>已分配新票据，准备出站</td><td>数秒</td></tr>
 * <tr><td>SIDING_PARKED</td><td>停放在侧线缓冲池</td><td>取决于站台空闲情况</td></tr>
 * <tr><td>DEADHEADING</td><td>回库/回送中（非载客）</td><td>数分钟</td></tr>
 * <tr><td>DESTROYED</td><td>已销毁或即将销毁</td><td>终态</td></tr>
 * </table>
 *
 * <h3>状态转换触发条件</h3>
 *
 * <ul>
 *   <li>{@code IN_SERVICE → TERMINATING}：列车到达 RouteStop.passType == TERMINATE 的节点
 *   <li>{@code TERMINATING → LAYOVER}：列车完成停站（开门/上下客）
 *   <li>{@code LAYOVER → LAYOVER_READY}：关门完成且位置合法
 *   <li>{@code LAYOVER_READY → DISPATCHING}：TicketAssigner 分配新任务
 *   <li>{@code DISPATCHING → IN_SERVICE}：发车成功，destination 已设置
 *   <li>{@code LAYOVER_READY → DEADHEADING}：ReclaimManager 分配 RETURN 票据
 *   <li>{@code DEADHEADING → DESTROYED}：到达 DSTY 目标节点
 *   <li>{@code * → DESTROYED}：列车被手动销毁或异常清理
 * </ul>
 *
 * @see LayoverRegistry
 * @see RuntimeDispatchService
 * @see org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.TicketAssigner
 */
public enum TrainLifecycleState {
  /** 正常运营中（执行 Route 任务）。 */
  IN_SERVICE,

  /** 正在进入终到流程（接近或停靠 TERM 站点）。 */
  TERMINATING,

  /** 在终到站/存车线待命（已完成停站，等待下一张票据）。 */
  LAYOVER,

  /** 待命已就绪（门已关、位置合法，可随时接受调度）。 */
  LAYOVER_READY,

  /** 已分配新票据，准备出站。 */
  DISPATCHING,

  /** 停放在侧线缓冲池中（等待腾挪回站台）。 */
  SIDING_PARKED,

  /** 回库/回送中（非载客任务）。 */
  DEADHEADING,

  /** 已销毁或即将销毁。 */
  DESTROYED
}
