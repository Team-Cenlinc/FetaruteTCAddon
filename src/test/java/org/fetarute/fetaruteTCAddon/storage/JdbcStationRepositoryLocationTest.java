package org.fetarute.fetaruteTCAddon.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.fetarute.fetaruteTCAddon.company.model.Operator;
import org.fetarute.fetaruteTCAddon.company.model.Station;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 没有坐标的车站（未设置位置或 --location-clear）必须能正常读出，不能拖垮整个运营商的车站列表。 */
class JdbcStationRepositoryLocationTest {

  @TempDir Path dir;

  @Test
  void stationsWithoutLocationAreListed() throws Exception {
    try (TransitTestStorage storage = TransitTestStorage.open(dir)) {
      Operator operator = storage.operator(storage.company("SURC"), "SURC", null);
      Station station = storage.station(operator, "PPK", "平坪口");

      List<Station> listed = storage.provider().stations().listByOperator(operator.id());

      assertEquals(List.of(station.id()), listed.stream().map(Station::id).toList());
      assertTrue(listed.get(0).location().isEmpty());
      assertTrue(
          storage.provider().stations().findById(station.id()).orElseThrow().location().isEmpty());
    }
  }
}
