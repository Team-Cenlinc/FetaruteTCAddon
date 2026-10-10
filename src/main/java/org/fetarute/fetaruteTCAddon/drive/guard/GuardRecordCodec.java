package org.fetarute.fetaruteTCAddon.drive.guard;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/** 车掌值乘记录的明细（{@code drive_task_records.detail_json}，mode 为 GUARD）：各站作业与列车走过的距离。 */
public final class GuardRecordCodec {

  /** 明细格式版本。 */
  public static final int FORMAT_VERSION = 1;

  private GuardRecordCodec() {}

  public static String encode(GuardScore score, double blocks) {
    JsonObject root = new JsonObject();
    root.addProperty("formatVersion", FORMAT_VERSION);
    root.addProperty("role", "guard");
    JsonArray stops = new JsonArray();
    for (GuardScore.Stop stop : score.stops()) {
      JsonObject item = new JsonObject();
      item.addProperty("station", stop.station());
      item.addProperty("forcedOpen", stop.forcedOpen());
      item.addProperty("forcedClose", stop.forcedClose());
      item.addProperty("forcedSignal", stop.forcedSignal());
      item.addProperty("wrongDoor", stop.wrongDoor());
      item.addProperty("closedEarly", stop.closedEarly());
      stop.closingWatch().ifPresent(passed -> item.addProperty("closingWatch", passed));
      stop.departureWatch().ifPresent(passed -> item.addProperty("departureWatch", passed));
      item.addProperty("incidents", stop.incidents());
      stops.add(item);
    }
    root.add("stops", stops);
    root.addProperty("blocks", Math.round(Math.max(0.0, blocks) * 10.0) / 10.0);
    return root.toString();
  }
}
