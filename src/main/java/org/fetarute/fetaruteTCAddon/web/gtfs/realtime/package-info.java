/**
 * GTFS-Realtime：TripUpdates、VehiclePositions、ServiceAlerts。
 *
 * <p>中间模型（{@link org.fetarute.fetaruteTCAddon.web.gtfs.realtime.GtfsRealtimeFeed}）与官方 {@code
 * gtfs-realtime.proto} 一一对应；protobuf 由 {@link
 * org.fetarute.fetaruteTCAddon.web.gtfs.realtime.GtfsRealtimeProtobuf} 手写编码，JSON 调试输出由 {@link
 * org.fetarute.fetaruteTCAddon.web.gtfs.realtime.GtfsRealtimeJson} 从同一份模型生成。插件运行时不引入 protobuf 依赖。
 *
 * <p>实时记录引用静态包里的 ID：车次走 {@code trip_id}，停靠点走 {@code stop_id}，版本号写在报文头，与 {@code /v1/meta} 的 {@code
 * gtfs.feedVersion} 相同。
 */
package org.fetarute.fetaruteTCAddon.web.gtfs.realtime;
