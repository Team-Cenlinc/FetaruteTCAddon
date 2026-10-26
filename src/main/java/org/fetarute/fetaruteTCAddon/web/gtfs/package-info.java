/**
 * GTFS 静态包与 GTFS-Realtime 的生成。
 *
 * <p>分层：
 *
 * <ul>
 *   <li>{@link org.fetarute.fetaruteTCAddon.web.gtfs.GtfsSnapshot}：一次读齐的输入（内存快照）；
 *   <li>{@link org.fetarute.fetaruteTCAddon.web.gtfs.GtfsFeedBuilder}：纯计算，把快照算成对外 ID 与各表的领域模型；
 *   <li>{@link org.fetarute.fetaruteTCAddon.web.gtfs.GtfsFeedTables}：排成 CSV 并打成确定性 zip；
 *   <li>{@link org.fetarute.fetaruteTCAddon.web.gtfs.GtfsFeedService}：按 300 秒缓存；
 *   <li>{@code realtime} 子包：protobuf/JSON 编码与三种实时 feed。
 * </ul>
 *
 * <p>坐标原点固定为西经 30°、赤道（一格一米，见 {@link org.fetarute.fetaruteTCAddon.web.gtfs.GtfsGeo}）；ID 规则见 {@link
 * org.fetarute.fetaruteTCAddon.web.gtfs.GtfsIds}。全部生成在 web 线程完成，不读库、不碰服务器主线程对象。
 */
package org.fetarute.fetaruteTCAddon.web.gtfs;
