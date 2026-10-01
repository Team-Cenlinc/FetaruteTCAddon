package org.fetarute.fetaruteTCAddon.display.pids.announce;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import org.fetarute.fetaruteTCAddon.api.route.RouteApi;
import org.fetarute.fetaruteTCAddon.display.pids.PidsRow;
import org.fetarute.fetaruteTCAddon.display.pids.PidsStationKey;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsDirectory;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsView;

/** 站台广播测试共用的行与名称目录。 */
final class AnnounceFixtures {

  static final Instant NOW = Instant.parse("2026-10-01T13:40:00Z");
  static final PidsStationKey TPC = new PidsStationKey("SURC", "TPC");

  private AnnounceFixtures() {}

  static PidsRow row(
      PidsRow.Status status,
      String train,
      int seconds,
      long delaySeconds,
      boolean passing,
      boolean terminating,
      boolean outOfService) {
    return new PidsRow(
        status,
        "MT",
        "SURC:MT:MT-3N",
        "HHU",
        Optional.of("SURC:HHU"),
        "2",
        NOW.plusSeconds(seconds),
        OptionalLong.of(delaySeconds),
        4,
        passing,
        terminating,
        outOfService,
        Optional.ofNullable(train));
  }

  static PidsRow running(PidsRow.Status status, String train, int seconds) {
    return row(status, train, seconds, 0, false, false, false);
  }

  static PidsRow cancelled(int seconds) {
    return new PidsRow(
        PidsRow.Status.CANCELLED,
        "MT",
        "SURC:MT:MT-3N",
        "HHU",
        Optional.of("SURC:HHU"),
        "2",
        NOW.plusSeconds(seconds),
        OptionalLong.empty(),
        4,
        false,
        false,
        false,
        Optional.empty());
  }

  static final class Directory implements PidsDirectory {
    @Override
    public Optional<PidsView.Names> stationName(String stationId) {
      return switch (stationId) {
        case "SURC:HHU" -> Optional.of(new PidsView.Names("新笛矢 · 壑湖", "Neo Fueya - Hor Huu"));
        case "SURC:TPC" -> Optional.of(new PidsView.Names("大港城", "The Port City"));
        default -> Optional.empty();
      };
    }

    @Override
    public Optional<LineStyle> line(String operatorCode, String lineCode) {
      return "MT".equals(lineCode) ? Optional.of(new LineStyle("MT", 0xD920D9)) : Optional.empty();
    }

    @Override
    public Optional<RouteApi.OperationType> serviceType(String routeId) {
      return Optional.empty();
    }

    @Override
    public List<PidsView.LineChip> linesServing(PidsStationKey station) {
      return List.of();
    }

    @Override
    public List<PidsView.LineChip> linesServingPlatform(PidsStationKey station, String platform) {
      return List.of();
    }
  }
}
