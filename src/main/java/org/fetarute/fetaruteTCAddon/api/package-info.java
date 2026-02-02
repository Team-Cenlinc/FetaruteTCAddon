/**
 * FetaruteTCAddon 公开 API 模块。
 *
 * <p>本包提供稳定的、面向外部插件的只读 API，适用于：
 *
 * <ul>
 *   <li>地图可视化插件（BlueMap/Dynmap/Squaremap）
 *   <li>信息显示插件（PIDS/站牌）
 *   <li>数据统计与监控工具
 * </ul>
 *
 * <h2>设计原则</h2>
 *
 * <ul>
 *   <li><b>最小暴露</b>：只暴露必要的只读数据，内部实现细节不公开
 *   <li><b>线程安全</b>：所有返回值为不可变快照，可安全跨线程使用
 *   <li><b>性能友好</b>：快照缓存避免频繁计算，支持批量查询
 *   <li><b>稳定契约</b>：API 签名变更遵循语义版本控制
 * </ul>
 *
 * <h2>入口点</h2>
 *
 * <p>通过 {@link org.fetarute.fetaruteTCAddon.api.FetaruteApi} 获取 API 实例：
 *
 * <pre>{@code
 * FetaruteApi api = FetaruteApi.getInstance();
 * if (api != null) {
 *     // 获取调度图
 *     api.graph().getSnapshot(worldId).ifPresent(snapshot -> {
 *         // 遍历节点
 *         snapshot.nodes().forEach((nodeId, node) -> { ... });
 *     });
 *
 *     // 获取列车状态
 *     api.trains().listActiveTrains(worldId).forEach(train -> {
 *         System.out.println(train.trainName() + " @ " + train.currentNode());
 *     });
 * }
 * }</pre>
 *
 * <h2>子模块</h2>
 *
 * <ul>
 *   <li>{@link org.fetarute.fetaruteTCAddon.api.graph.GraphApi} - 调度图（节点/边/路径）
 *   <li>{@link org.fetarute.fetaruteTCAddon.api.train.TrainApi} - 列车状态与位置
 *   <li>{@link org.fetarute.fetaruteTCAddon.api.route.RouteApi} - 路线与站点信息
 *   <li>{@link org.fetarute.fetaruteTCAddon.api.occupancy.OccupancyApi} - 占用与信号状态
 *   <li>{@link org.fetarute.fetaruteTCAddon.api.station.StationApi} - 站点信息
 *   <li>{@link org.fetarute.fetaruteTCAddon.api.operator.OperatorApi} - 运营商信息
 *   <li>{@link org.fetarute.fetaruteTCAddon.api.line.LineApi} - 线路信息
 *   <li>{@link org.fetarute.fetaruteTCAddon.api.eta.EtaApi} - ETA 与站牌列表
 * </ul>
 *
 * @see org.fetarute.fetaruteTCAddon.api.FetaruteApi
 */
package org.fetarute.fetaruteTCAddon.api;
