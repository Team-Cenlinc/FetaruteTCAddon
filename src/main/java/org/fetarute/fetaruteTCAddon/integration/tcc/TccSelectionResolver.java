package org.fetarute.fetaruteTCAddon.integration.tcc;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.explore.RailBlockPos;

/**
 * TCCoasters 选中节点/轨道的读取适配器（可选集成）。
 *
 * <p>约束：本插件不硬依赖 TCCoasters；通过 softdepend + 反射读取玩家编辑器状态，避免在未安装 TCC 时发生 NoClassDefFoundError。
 */
public final class TccSelectionResolver {

  private static final String PLUGIN_NAME = "TCCoasters";
  private static final String CLASS_TCCOASTERS = "com.bergerkiller.bukkit.coasters.TCCoasters";

  private TccSelectionResolver() {}

  public record TccCoasterSelection(RailBlockPos seedRail, List<RailBlockPos> coasterNodes) {
    public TccCoasterSelection {
      if (seedRail == null) {
        throw new IllegalArgumentException("seedRail 不能为空");
      }
      coasterNodes = coasterNodes != null ? List.copyOf(coasterNodes) : List.of();
    }
  }

  /**
   * 读取玩家在 TCC 编辑器中“当前选中”的轨道方块坐标（优先使用最后编辑的节点）。
   *
   * <p>回退：若没有选中节点，尝试读取玩家正在看向的轨道方块。
   *
   * <p>用途：用于 HERE 模式定位起点轨道锚点（seedRails）。
   */
  public static Optional<RailBlockPos> findSelectedRailBlock(Player player) {
    if (player == null) {
      return Optional.empty();
    }

    Plugin plugin = Bukkit.getPluginManager().getPlugin(PLUGIN_NAME);
    if (plugin == null || !plugin.isEnabled()) {
      return Optional.empty();
    }

    try {
      Class<?> tccClass = Class.forName(CLASS_TCCOASTERS);
      if (!tccClass.isInstance(plugin)) {
        return Optional.empty();
      }

      Object editState =
          invoke(plugin, tccClass, "getEditState", new Class<?>[] {Player.class}, player);
      if (editState == null) {
        return Optional.empty();
      }

      Boolean hasEditedNodes = (Boolean) invoke(editState, editState.getClass(), "hasEditedNodes");
      if (Boolean.TRUE.equals(hasEditedNodes)) {
        Object lastEditedNode = invoke(editState, editState.getClass(), "getLastEditedNode");
        Optional<RailBlockPos> pos = railPosFromTrackNode(lastEditedNode);
        if (pos.isPresent()) {
          return pos;
        }
      }

      Object railInfo = invoke(editState, editState.getClass(), "findLookingAtRailBlock");
      if (railInfo == null) {
        return Optional.empty();
      }
      Object rail = readField(railInfo, "rail");
      return railPosFromIntVector3(rail);
    } catch (ReflectiveOperationException | ClassCastException ex) {
      return Optional.empty();
    }
  }

  /**
   * 读取玩家在 TCC 编辑器中“当前选中的 coaster”信息（用于无牌子线网建图）。
   *
   * <p>优先使用最后编辑的 TrackNode；其次是正在看向的 TrackNode；最后回退到“看向的 rail block”（若有）。
   *
   * <p>用途：把 coaster 的所有 TrackNode 注入为预置节点，避免 TCC 线网没有任何牌子时 build HERE 扫不到 Node。
   */
  public static Optional<TccCoasterSelection> findSelectedCoaster(Player player) {
    if (player == null) {
      return Optional.empty();
    }

    Plugin plugin = Bukkit.getPluginManager().getPlugin(PLUGIN_NAME);
    if (plugin == null || !plugin.isEnabled()) {
      return Optional.empty();
    }

    try {
      Class<?> tccClass = Class.forName(CLASS_TCCOASTERS);
      if (!tccClass.isInstance(plugin)) {
        return Optional.empty();
      }

      Object editState =
          invoke(plugin, tccClass, "getEditState", new Class<?>[] {Player.class}, player);
      if (editState == null) {
        return Optional.empty();
      }

      Object selectedNode = null;
      Boolean hasEditedNodes = (Boolean) invoke(editState, editState.getClass(), "hasEditedNodes");
      if (Boolean.TRUE.equals(hasEditedNodes)) {
        selectedNode = invoke(editState, editState.getClass(), "getLastEditedNode");
      }
      if (selectedNode == null) {
        selectedNode = invoke(editState, editState.getClass(), "findLookingAt");
      }

      Optional<RailBlockPos> seedRail = railPosFromTrackNode(selectedNode);
      if (seedRail.isEmpty()) {
        Object railInfo = invoke(editState, editState.getClass(), "findLookingAtRailBlock");
        if (railInfo == null) {
          return Optional.empty();
        }
        Object rail = readField(railInfo, "rail");
        seedRail = railPosFromIntVector3(rail);
        if (seedRail.isEmpty()) {
          return Optional.empty();
        }
        return Optional.of(new TccCoasterSelection(seedRail.get(), List.of()));
      }

      Object coaster = invoke(selectedNode, selectedNode.getClass(), "getCoaster");
      if (coaster == null) {
        return Optional.of(new TccCoasterSelection(seedRail.get(), List.of()));
      }

      @SuppressWarnings("unchecked")
      List<Object> nodes = (List<Object>) invoke(coaster, coaster.getClass(), "getNodes");
      if (nodes == null || nodes.isEmpty()) {
        return Optional.of(new TccCoasterSelection(seedRail.get(), List.of()));
      }

      List<RailBlockPos> rails = new ArrayList<>(nodes.size());
      for (Object node : nodes) {
        railPosFromTrackNode(node).ifPresent(rails::add);
      }
      return Optional.of(new TccCoasterSelection(seedRail.get(), rails));
    } catch (ReflectiveOperationException | ClassCastException ex) {
      return Optional.empty();
    }
  }

  private static Optional<RailBlockPos> railPosFromTrackNode(Object trackNode) {
    if (trackNode == null) {
      return Optional.empty();
    }
    try {
      Class<?> nodeClass = trackNode.getClass();
      Object rail =
          invoke(trackNode, nodeClass, "getRailBlock", new Class<?>[] {boolean.class}, true);
      Optional<RailBlockPos> pos = railPosFromIntVector3(rail);
      if (pos.isPresent()) {
        return pos;
      }
      Object positionBlock = invoke(trackNode, nodeClass, "getPositionBlock");
      return railPosFromIntVector3(positionBlock);
    } catch (ReflectiveOperationException | ClassCastException ex) {
      return Optional.empty();
    }
  }

  private static Optional<RailBlockPos> railPosFromIntVector3(Object intVector3) {
    if (intVector3 == null) {
      return Optional.empty();
    }
    try {
      int x = (int) readPublicField(intVector3, "x");
      int y = (int) readPublicField(intVector3, "y");
      int z = (int) readPublicField(intVector3, "z");
      return Optional.of(new RailBlockPos(x, y, z));
    } catch (ReflectiveOperationException | ClassCastException ex) {
      return Optional.empty();
    }
  }

  private static Object invoke(Object target, Class<?> type, String method, Object... args)
      throws ReflectiveOperationException {
    Class<?>[] signature = new Class<?>[args.length];
    for (int i = 0; i < args.length; i++) {
      signature[i] = args[i] != null ? args[i].getClass() : Object.class;
    }
    Method m = type.getMethod(method, signature);
    return m.invoke(target, args);
  }

  private static Object invoke(
      Object target, Class<?> type, String method, Class<?>[] signature, Object... args)
      throws ReflectiveOperationException {
    Method m = type.getMethod(method, signature);
    return m.invoke(target, args);
  }

  private static Object readField(Object target, String field) throws ReflectiveOperationException {
    Field f = target.getClass().getField(field);
    return f.get(target);
  }

  private static Object readPublicField(Object target, String field)
      throws ReflectiveOperationException {
    Field f = target.getClass().getField(field);
    return f.get(target);
  }
}
