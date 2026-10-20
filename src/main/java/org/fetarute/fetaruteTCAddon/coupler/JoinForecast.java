package org.fetarute.fetaruteTCAddon.coupler;

import java.util.Objects;

/**
 * 一列运行中的车在本交路前方（或此刻停着的这一站）要与另一列车联挂（{@code COPL}）：在哪一站、自己几节、挂上的几节、对方是不是已经停在站台。给站台屏的联挂页用。
 *
 * <p>回程支线车（被挂上主干的一方）看前方的联挂站；主干车看本站或前方的联挂站。节数读不到时为 0。
 *
 * @param routeId 这列车此刻的交路（{@code 运营商:线路:交路}，与站牌行同一口径）
 * @param stopIndex 联挂站在这条交路里的下标（与站牌行的停靠序号同一口径）
 * @param stationId 联挂站（{@code 运营商:站码}）
 * @param station 联挂站显示名
 * @param trunk 这列车是主干（另一列车挂上来）；否则是回程支线车
 * @param ownCars 这列车的节数
 * @param joiningCars 另一列车的节数；读不到时为 0
 * @param joiningStanding 另一列车已经停在联挂站
 */
public record JoinForecast(
    String routeId,
    int stopIndex,
    String stationId,
    String station,
    boolean trunk,
    int ownCars,
    int joiningCars,
    boolean joiningStanding) {

  public JoinForecast {
    Objects.requireNonNull(routeId, "routeId");
    Objects.requireNonNull(stationId, "stationId");
    station = station == null ? "" : station;
    ownCars = Math.max(0, ownCars);
    joiningCars = Math.max(0, joiningCars);
  }
}
