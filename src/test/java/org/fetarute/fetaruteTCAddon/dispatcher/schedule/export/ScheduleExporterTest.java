package org.fetarute.fetaruteTCAddon.dispatcher.schedule.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.model.ScheduleWindow;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.model.ScheduledStop;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.model.ServiceTrip;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.model.TripSource;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnDepot;
import org.junit.jupiter.api.Test;

class ScheduleExporterTest {

  @Test
  void csvHeaderIsStable() {
    String csv = ScheduleCsvExporter.export(window());

    assertEquals(ScheduleCsvExporter.HEADER, csv.lines().findFirst().orElseThrow());
  }

  @Test
  void csvKeepsStopOrderAndReadableDepotCandidates() {
    String csv = ScheduleCsvExporter.export(window());
    List<String> lines = csv.lines().toList();

    assertTrue(lines.get(1).contains(",0,AAA,OP:S:AAA:1,"));
    assertTrue(lines.get(2).contains(",1,BBB,OP:S:BBB:1,"));
    assertTrue(lines.get(1).contains("OP:D:DEPOT:1*2;OP:D:DEPOT:2"));
  }

  @Test
  void jsonExportsEpochMillisAndOmitsEmptyOptionalFields() {
    String json = ScheduleJsonExporter.export(window());
    JsonObject root = JsonParser.parseString(json).getAsJsonObject();
    JsonObject trip = root.getAsJsonArray("trips").get(0).getAsJsonObject();
    JsonObject stop = trip.getAsJsonArray("planned_stops").get(0).getAsJsonObject();

    assertEquals(
        Instant.parse("2026-02-01T00:00:00Z").toEpochMilli(),
        root.get("window_start_epoch_millis").getAsLong());
    assertEquals("SCHEDULED", trip.get("source").getAsString());
    assertFalse(trip.has("direction"));
    assertFalse(trip.has("notes"));
    assertEquals(
        Instant.parse("2026-02-01T00:05:00Z").toEpochMilli(),
        trip.get("planned_departure_epoch_millis").getAsLong());
    assertEquals("AAA", stop.get("station_code").getAsString());
    assertFalse(json.contains(":null"));
  }

  private static ScheduleWindow window() {
    Instant departure = Instant.parse("2026-02-01T00:05:00Z");
    ServiceTrip trip =
        new ServiceTrip(
            "trip-1",
            TripSource.SCHEDULED,
            UUID.fromString("00000000-0000-0000-0000-000000000001"),
            "C1",
            UUID.fromString("00000000-0000-0000-0000-000000000002"),
            "OP",
            UUID.fromString("00000000-0000-0000-0000-000000000003"),
            "L1",
            UUID.fromString("00000000-0000-0000-0000-000000000004"),
            "R1",
            Optional.empty(),
            departure,
            List.of(
                new ScheduledStop(
                    0,
                    Optional.of("AAA"),
                    Optional.of("OP:S:AAA:1"),
                    Optional.of(departure),
                    Optional.of(departure.plusSeconds(30)),
                    Optional.of(Duration.ofSeconds(30)),
                    Optional.empty()),
                new ScheduledStop(
                    1,
                    Optional.of("BBB"),
                    Optional.of("OP:S:BBB:1"),
                    Optional.of(departure.plusSeconds(90)),
                    Optional.of(departure.plusSeconds(120)),
                    Optional.of(Duration.ofSeconds(30)),
                    Optional.of("board"))),
            5,
            List.of(new SpawnDepot("OP:D:DEPOT:1", 2), new SpawnDepot("OP:D:DEPOT:2", 1)),
            Optional.empty(),
            Optional.empty());
    return new ScheduleWindow(
        Instant.parse("2026-02-01T00:00:01Z"),
        Instant.parse("2026-02-01T00:00:00Z"),
        Instant.parse("2026-02-01T01:00:00Z"),
        List.of(trip));
  }
}
