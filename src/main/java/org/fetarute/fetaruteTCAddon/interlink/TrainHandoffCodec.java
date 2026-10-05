package org.fetarute.fetaruteTCAddon.interlink;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/** 整车快照的 JSON。带 {@code formatVersion}：版本不认识、缺字段或格式不对一律解码失败，对端拒收（列车留在本服）。 */
public final class TrainHandoffCodec {

  /** 快照格式版本。 */
  public static final int FORMAT_VERSION = 1;

  private TrainHandoffCodec() {}

  public static String encode(TrainHandoff handoff) {
    JsonObject root = new JsonObject();
    root.addProperty("formatVersion", FORMAT_VERSION);
    root.addProperty("handoffId", handoff.handoffId());
    root.addProperty("fromServer", handoff.fromServer());
    root.addProperty("toServer", handoff.toServer());
    root.addProperty("grantId", handoff.grantId());
    root.addProperty("trainUid", handoff.trainUid());
    root.addProperty("trainName", handoff.trainName());
    root.addProperty("boundaryNodeId", handoff.boundaryNodeId());
    root.addProperty("groupConfig", handoff.groupConfig());
    handoff.routeId().ifPresent(id -> root.addProperty("routeId", id.toString()));
    root.addProperty("routeIndex", handoff.routeIndex());
    handoff
        .timetable()
        .ifPresent(
            t -> {
              JsonObject tt = new JsonObject();
              tt.addProperty("timetableId", t.timetableId().toString());
              tt.addProperty("tripCode", t.tripCode());
              tt.addProperty("serviceDate", t.serviceDate().toString());
              tt.addProperty("delaySeconds", t.delaySeconds());
              root.add("timetable", tt);
            });
    handoff
        .driver()
        .ifPresent(
            d -> {
              JsonObject driver = new JsonObject();
              driver.addProperty("playerId", d.playerId().toString());
              driver.addProperty("memberIndex", d.memberIndex());
              driver.addProperty("mode", d.mode());
              root.add("driver", driver);
            });
    root.addProperty("speedBps", handoff.speedBps());
    root.addProperty("createdAt", handoff.createdAt().toString());
    return root.toString();
  }

  /** 解码；版本不认识、缺字段或格式不对时为空。 */
  public static Optional<TrainHandoff> decode(String json) {
    if (json == null || json.isBlank()) {
      return Optional.empty();
    }
    try {
      JsonObject root = JsonParser.parseString(json).getAsJsonObject();
      if (!root.has("formatVersion") || root.get("formatVersion").getAsInt() != FORMAT_VERSION) {
        return Optional.empty();
      }
      Optional<TrainHandoff.Timetable> timetable = Optional.empty();
      if (root.has("timetable")) {
        JsonObject tt = root.getAsJsonObject("timetable");
        timetable =
            Optional.of(
                new TrainHandoff.Timetable(
                    UUID.fromString(string(tt, "timetableId")),
                    string(tt, "tripCode"),
                    LocalDate.parse(string(tt, "serviceDate")),
                    tt.get("delaySeconds").getAsLong()));
      }
      Optional<TrainHandoff.Driver> driver = Optional.empty();
      if (root.has("driver")) {
        JsonObject d = root.getAsJsonObject("driver");
        driver =
            Optional.of(
                new TrainHandoff.Driver(
                    UUID.fromString(string(d, "playerId")),
                    d.get("memberIndex").getAsInt(),
                    string(d, "mode")));
      }
      return Optional.of(
          new TrainHandoff(
              string(root, "handoffId"),
              string(root, "fromServer"),
              string(root, "toServer"),
              optionalString(root, "grantId"),
              string(root, "trainUid"),
              optionalString(root, "trainName"),
              string(root, "boundaryNodeId"),
              string(root, "groupConfig"),
              root.has("routeId")
                  ? Optional.of(UUID.fromString(string(root, "routeId")))
                  : Optional.empty(),
              root.get("routeIndex").getAsInt(),
              timetable,
              driver,
              root.get("speedBps").getAsDouble(),
              Instant.parse(string(root, "createdAt"))));
    } catch (RuntimeException ex) {
      return Optional.empty();
    }
  }

  private static String string(JsonObject object, String key) {
    JsonElement element = object.get(key);
    if (element == null || element.isJsonNull()) {
      throw new IllegalArgumentException("缺少字段 " + key);
    }
    return element.getAsString();
  }

  private static String optionalString(JsonObject object, String key) {
    JsonElement element = object.get(key);
    return element == null || element.isJsonNull() ? "" : element.getAsString();
  }
}
