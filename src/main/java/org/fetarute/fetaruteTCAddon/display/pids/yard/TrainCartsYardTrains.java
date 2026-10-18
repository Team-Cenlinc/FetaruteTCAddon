package org.fetarute.fetaruteTCAddon.display.pids.yard;

import com.bergerkiller.bukkit.tc.controller.MinecartGroup;
import com.bergerkiller.bukkit.tc.controller.MinecartGroupStore;
import com.bergerkiller.bukkit.tc.controller.MinecartMember;
import com.bergerkiller.bukkit.tc.controller.components.RailState;
import com.bergerkiller.bukkit.tc.properties.TrainProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking.RailFootprintCell;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RouteProgressRegistry;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.TrainTagHelper;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableAssignment;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableService;
import org.fetarute.fetaruteTCAddon.drive.session.ManagedTrains;

/**
 * 从 TrainCarts 读出一个世界里已加载的调度列车：各节车厢所在的轨道方块、车次号、线路色与晚点。
 *
 * <p>只读，在主线程调用。没加载的列车不在 TrainCarts 的列车表里，站场图上也就不画。
 */
public final class TrainCartsYardTrains implements Function<UUID, List<YardTrain>> {

  private final Supplier<Optional<TimetableService>> timetables;
  private final BiFunction<String, String, Optional<Integer>> lineColors;

  /**
   * @param timetables 按表运行服务（车次号与晚点）；没有时车次窗写列车名、不判晚点
   * @param lineColors （运营商代码, 线路代码）→ 线路色
   */
  public TrainCartsYardTrains(
      Supplier<Optional<TimetableService>> timetables,
      BiFunction<String, String, Optional<Integer>> lineColors) {
    this.timetables = Objects.requireNonNull(timetables, "timetables");
    this.lineColors = Objects.requireNonNull(lineColors, "lineColors");
  }

  /** 只读车厢位置；车次号、线路色与晚点留到车身落在某张站场图上时才查。 */
  @Override
  public List<YardTrain> apply(UUID worldId) {
    List<YardTrain> out = new ArrayList<>();
    for (MinecartGroup group : MinecartGroupStore.getGroups()) {
      if (group == null || group.isEmpty() || group.isUnloaded()) {
        continue;
      }
      World world = group.getWorld();
      if (world == null || !world.getUID().equals(worldId)) {
        continue;
      }
      TrainProperties properties = group.getProperties();
      if (!ManagedTrains.isFtaManaged(properties)) {
        continue;
      }
      String name = properties.getTrainName();
      out.add(new YardTrain(name, cars(group), () -> details(name, properties)));
    }
    return out;
  }

  private YardTrain.Details details(String name, TrainProperties properties) {
    Optional<TimetableService> timetable = timetables.get();
    return new YardTrain.Details(
        timetable
            .flatMap(service -> service.assignmentOf(name))
            .map(TimetableAssignment::tripCode)
            .filter(code -> code != null && !code.isBlank())
            .orElse(name),
        lineColor(properties),
        timetable.map(service -> service.currentDelaySeconds(name).orElse(0L)).orElse(0L));
  }

  private Optional<Integer> lineColor(TrainProperties properties) {
    Optional<String> operator =
        TrainTagHelper.readTagValue(properties, RouteProgressRegistry.TAG_OPERATOR_CODE);
    Optional<String> line =
        TrainTagHelper.readTagValue(properties, RouteProgressRegistry.TAG_LINE_CODE);
    if (operator.isEmpty() || line.isEmpty()) {
      return Optional.empty();
    }
    return lineColors.apply(operator.get(), line.get());
  }

  private static List<RailFootprintCell> cars(MinecartGroup group) {
    List<RailFootprintCell> cars = new ArrayList<>(group.size());
    for (MinecartMember<?> member : group) {
      if (member == null || member.isUnloaded() || member.getRailTracker() == null) {
        continue;
      }
      RailState state = member.getRailTracker().getState();
      Block rail = state == null ? null : state.railBlock();
      if (rail != null) {
        cars.add(new RailFootprintCell(rail.getX(), rail.getY(), rail.getZ()));
      }
    }
    return cars;
  }
}
