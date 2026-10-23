package org.fetarute.fetaruteTCAddon.coupler;

import com.bergerkiller.bukkit.tc.properties.TrainProperties;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.call.CallService;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RouteProgressRegistry;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.TrainCartsTimetableDutyStore;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.TrainSpawnTagInitializer;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.TrainTagHelper;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SimpleTicketAssigner;

/**
 * 受管列车拆下的那一半怎么改标签：TrainCarts 拆分时把原车的标签整份复制给了它，要换上新身份，去掉只属于原车这一趟的标记（时刻表交路、叫车、
 * 提前出车扣车、驾驶员、发车控制的瞬时标记、出车事务墓碑、原车的同股道列车对）；交路、进度、圈数、编组与车辆参数照搬。纯计算，另由 {@link #applyTo} 写进列车。
 *
 * @param writes 要写的标签（键 → 值）
 * @param removals 要删的标签键
 */
public record SplitTagPlan(Map<String, String> writes, Set<String> removals) {

  /** 运行 ID 标签（出车时与全局 ID 取同一个值）。 */
  static final String TAG_RUN_ID = "FTA_RUN_ID";

  /** 上一次接走它的发车票。 */
  static final String TAG_TICKET_ID = "FTA_TICKET_ID";

  public SplitTagPlan {
    writes = Map.copyOf(Objects.requireNonNull(writes, "writes"));
    removals = Set.copyOf(Objects.requireNonNull(removals, "removals"));
  }

  /**
   * 拆下的那一半的改法。
   *
   * @param trainName 新车名
   * @param runId 新的运行 ID（同时作全局 ID）
   */
  public static SplitTagPlan forDetached(String trainName, UUID runId) {
    Objects.requireNonNull(trainName, "trainName");
    Objects.requireNonNull(runId, "runId");
    Map<String, String> writes = new LinkedHashMap<>();
    writes.put(RouteProgressRegistry.TAG_TRAIN_NAME, trainName);
    writes.put(TAG_RUN_ID, runId.toString());
    writes.put(TrainSpawnTagInitializer.TAG_TRAIN_UID, runId.toString());
    Set<String> removals = new LinkedHashSet<>();
    removals.add(TrainCartsTimetableDutyStore.TAG_DUTY);
    removals.add(SimpleTicketAssigner.TAG_CALLED_TRAIN);
    removals.add(SimpleTicketAssigner.TAG_CALL_TURNBACK);
    removals.add(CallService.TAG_CALL_PLATFORM);
    removals.add(CallService.TAG_CALL_ROUTE);
    removals.add(CallService.TAG_CALL_EXTRA);
    removals.add(SimpleTicketAssigner.TAG_EARLY_SPAWN_HOLD);
    removals.add(TAG_TICKET_ID);
    removals.add(TrainSpawnTagInitializer.TAG_MATERIALIZED_ROLLBACK_PENDING);
    removals.addAll(TrainSpawnTagInitializer.transientControlTagKeys());
    removals.add(CouplerService.TAG_CO_LOCATED);
    removals.removeAll(writes.keySet());
    return new SplitTagPlan(writes, removals);
  }

  /** 把改法写进列车（每节车厢都改：TrainCarts 的标签按车厢存）。 */
  public void applyTo(TrainProperties properties) {
    Objects.requireNonNull(properties, "properties");
    for (String key : removals) {
      TrainTagHelper.removeTagKey(properties, key);
    }
    writes.forEach((key, value) -> TrainTagHelper.writeTag(properties, key, value));
  }

  /**
   * 拆下的那一半的车名：沿用原车名的前缀（运营商、线路、运行图与终点），末尾的 4 位序号由新的运行 ID 重新取；原车名不是这个格式时在末尾加序号。
   *
   * @param sourceName 原车名
   * @param runId 新的运行 ID
   */
  public static String detachedName(String sourceName, UUID runId) {
    Objects.requireNonNull(runId, "runId");
    int raw = runId.hashCode();
    String seq =
        String.format(Locale.ROOT, "%04d", (raw == Integer.MIN_VALUE ? 0 : Math.abs(raw)) % 10000);
    String base = sourceName == null ? "" : sourceName.trim();
    int dash = base.lastIndexOf('-');
    if (dash > 0 && base.substring(dash + 1).matches("\\d{4}")) {
      return base.substring(0, dash + 1) + seq;
    }
    return base.isEmpty() ? "TRAIN-" + seq : base + "-" + seq;
  }
}
