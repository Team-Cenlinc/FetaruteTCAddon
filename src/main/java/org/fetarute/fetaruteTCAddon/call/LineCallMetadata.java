package org.fetarute.fetaruteTCAddon.call;

import java.util.Map;
import java.util.OptionalInt;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.LineSpawnMetadata;

/**
 * 线路 metadata 里的叫车开关。
 *
 * <ul>
 *   <li>{@code allow_player_call}：是否允许玩家叫车；缺省为否。
 *   <li>{@code call_max_trains}：同时最多几辆叫来的车（含尚未派出的叫车）；缺省按 {@code call.default-max-trains}。
 * </ul>
 */
public final class LineCallMetadata {

  public static final String KEY_ALLOW_PLAYER_CALL = "allow_player_call";
  public static final String KEY_CALL_MAX_TRAINS = "call_max_trains";

  private LineCallMetadata() {}

  /** 线路是否允许玩家叫车：只认显式的 true。 */
  public static boolean allowsPlayerCall(Map<String, Object> metadata) {
    return LineSpawnMetadata.readBoolean(metadata, KEY_ALLOW_PLAYER_CALL).orElse(false);
  }

  /** 线路写明的叫车车数上限；未写或不是正数时为空。 */
  public static OptionalInt maxTrains(Map<String, Object> metadata) {
    return LineSpawnMetadata.readInt(metadata, KEY_CALL_MAX_TRAINS)
        .filter(value -> value > 0)
        .map(OptionalInt::of)
        .orElse(OptionalInt.empty());
  }
}
