package org.fetarute.fetaruteTCAddon.web.gtfs;

import java.util.Optional;

/**
 * GTFS 的标识符规则：所有对外发布的 ID 都在这里拼，静态包与实时源共用同一套，任何一边都不能自己另拼一份。
 *
 * <p>ID 一律带本服的服务器 ID 前缀（{@code {S}:...}）：几台服务器的 feed 被中转合并后仍然不会撞号。没配 {@code server-id} 时前缀连同冒号一起省掉，
 * 只适合单服部署；发布之后再补 {@code server-id} 会让所有消费者手里的 ID 全部换掉。
 *
 * <p>车次 ID 里带时刻表所属的线路与时刻表代码：车次号只保证在一张表内唯一，时刻表代码只在线路内唯一。直通运转在换线站拆段，第一段不带后缀，之后各段依次加 {@code ~2}、{@code
 * ~3}。
 */
public final class GtfsIds {

  /** 日历服务 ID 的后缀：插件没有星期或日期有效期，全网一张表天天开。 */
  static final String DAILY = "DAILY";

  private final String prefix;

  /**
   * @param serverId 配置里的 {@code server-id}；没配（或空白）时 ID 不带前缀
   */
  public GtfsIds(Optional<String> serverId) {
    String id = serverId == null ? "" : serverId.map(String::trim).orElse("");
    this.prefix = id.isEmpty() ? "" : id + ":";
  }

  /** 本服 ID 前缀（{@code server-id:}）；没配时为空串。 */
  public String prefix() {
    return prefix;
  }

  /** 运营商（agency）：{@code {S}:{OP}}。 */
  public String agency(String operatorCode) {
    return prefix + require(operatorCode, "operatorCode");
  }

  /** 线路（route）：{@code {S}:{OP}:{LINE}}。 */
  public String route(String operatorCode, String lineCode) {
    return prefix + require(operatorCode, "operatorCode") + ":" + require(lineCode, "lineCode");
  }

  /** 车站（location_type=1）：{@code {S}:{OP}:S:{CODE}}。 */
  public String station(String operatorCode, String stationCode) {
    return prefix
        + require(operatorCode, "operatorCode")
        + ":S:"
        + require(stationCode, "stationCode");
  }

  /** 股道（location_type=0）：{@code {S}:} 加调度图节点 ID。 */
  public String stop(String nodeId) {
    return prefix + require(nodeId, "nodeId");
  }

  /** 站台未定的车站级停靠点：{@code {S}:{OP}:S:{CODE}:DYN}。 */
  public String dynamicStop(String operatorCode, String stationCode) {
    return station(operatorCode, stationCode) + ":DYN";
  }

  /**
   * 车次：{@code {S}:{OP}:{时刻表所属线路}:{时刻表代码}:{车次号}}；换线拆段后的第二段起加 {@code ~2}、{@code ~3}。
   *
   * @param segment 分段序号（0 起）
   */
  public String trip(
      String operatorCode, String lineCode, String timetableCode, String tripCode, int segment) {
    String base =
        prefix
            + require(operatorCode, "operatorCode")
            + ":"
            + require(lineCode, "lineCode")
            + ":"
            + require(timetableCode, "timetableCode")
            + ":"
            + require(tripCode, "tripCode");
    return segment <= 0 ? base : base + "~" + (segment + 1);
  }

  /** 日历服务：{@code {S}:DAILY}。 */
  public String service() {
    return prefix + DAILY;
  }

  private static String require(String value, String name) {
    String trimmed = value == null ? "" : value.trim();
    if (trimmed.isEmpty()) {
      throw new IllegalArgumentException(name + " 不能为空");
    }
    return trimmed;
  }
}
