package org.fetarute.fetaruteTCAddon.drive.driver.record;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.fetarute.fetaruteTCAddon.drive.driver.score.StopScore;
import org.fetarute.fetaruteTCAddon.drive.driver.score.TaskScore;

/** 任务记录明细的 JSON：带 {@code formatVersion}，日后加字段只增不改。 */
public final class DriveTaskRecordCodec {

  /** 明细格式版本。 */
  public static final int FORMAT_VERSION = 1;

  private DriveTaskRecordCodec() {}

  /** 把成绩明细写成 JSON。 */
  public static String encode(TaskScore score) {
    JsonObject root = new JsonObject();
    root.addProperty("formatVersion", FORMAT_VERSION);
    JsonArray stops = new JsonArray();
    for (StopScore stop : score.stops()) {
      JsonObject item = new JsonObject();
      item.addProperty("station", stop.station());
      if (Double.isFinite(stop.offsetBlocks())) {
        item.addProperty("offsetBlocks", Math.round(stop.offsetBlocks() * 100.0) / 100.0);
      }
      item.addProperty("window", stop.window().name());
      item.addProperty("wrongDoor", stop.wrongDoor());
      item.addProperty("doorsTakenOver", stop.doorsTakenOver());
      stops.add(item);
    }
    root.add("stops", stops);
    root.addProperty("serviceInterventions", score.serviceInterventions());
    root.addProperty("emergencyInterventions", score.emergencyInterventions());
    root.addProperty("forcedStops", score.forcedStops());
    root.addProperty("signalConfirmations", score.signalConfirmations());
    root.addProperty("signalMisses", score.signalMisses());
    root.addProperty(
        "signalReactionSeconds", Math.round(score.signalReactionSeconds() * 100.0) / 100.0);
    root.addProperty("vigilanceTrips", score.vigilanceTrips());
    root.addProperty("lateDepartureConfirmations", score.lateDepartureConfirmations());
    score.delayGainedSeconds().ifPresent(value -> root.addProperty("delayGainedSeconds", value));
    return root.toString();
  }
}
