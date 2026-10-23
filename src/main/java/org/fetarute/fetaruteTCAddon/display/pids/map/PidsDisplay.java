package org.fetarute.fetaruteTCAddon.display.pids.map;

import com.bergerkiller.bukkit.common.events.map.MapClickEvent;
import com.bergerkiller.bukkit.common.map.MapDisplay;
import com.bergerkiller.bukkit.common.map.MapSessionMode;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.fetarute.fetaruteTCAddon.FetaruteTCAddon;
import org.fetarute.fetaruteTCAddon.display.pids.PidsComposer;
import org.fetarute.fetaruteTCAddon.display.pids.PidsService;
import org.fetarute.fetaruteTCAddon.signaller.console.ConsoleTarget;

/**
 * 挂在展示框上的站台屏，由 BKC 在有玩家看得到时创建。
 *
 * <ul>
 *   <li>{@link MapSessionMode#VIEWING}：没人看时 BKC 结束会话，不占任何开销；有人走近再重新创建。
 *   <li>每隔 {@code render.check-interval-ticks} 问一次服务该显示什么；内容标识与上次相同就什么都不做。
 *   <li>内容变了才取帧：换算好的调色板帧按内容标识在各屏间共享（{@link PidsFrameCache}），缓存里没有才渲染整帧、换算；
 *       再与已写入画布的帧比对，按地图块得出变化的矩形（{@link PidsFrameDiff}）。 集中的一次写完；散在各处的每 tick 写一块，免得 BKC
 *       把它们合成一个大矩形、连中间没变的地图一起重发。
 *   <li>服务每次现取：{@code /fta reload} 会换掉服务实例（字体、配置都可能变），换了就整帧重画。取不到服务时保持原画面。
 *   <li>点击：只有正显示信号员控制台（{@code signalbox-*}）时响应。右键按画面上的版面认出点到的东西，左键一律是取消选择； 同一玩家同一 tick
 *       只认一次，手持配置棍的玩家照旧交给配置棍处理。交给信号员模块后当拍重画这一块屏，不等下一次检查。
 * </ul>
 *
 * <p>BKC 通过反射创建实例，必须保留公开的无参构造器。
 */
public class PidsDisplay extends MapDisplay {

  private static final int DEFAULT_INTERVAL_TICKS = 20;

  /** 已写入画布的帧；为空表示还没写过。 */
  private byte[] written;

  /** 要显示的帧。 */
  private byte[] target;

  private final ArrayDeque<PidsFrameDiff.Region> pending = new ArrayDeque<>();
  private Object shownKey;
  private PidsService shownBy;
  private int ticks;
  private final PidsClickDedupe clicks = new PidsClickDedupe();

  @Override
  public void onAttached() {
    setSessionMode(MapSessionMode.VIEWING);
    setUpdateWithoutViewers(false);
    reset();
    refresh();
  }

  @Override
  public void onDetached() {
    reset();
  }

  @Override
  public void onTick() {
    writeNext();
    int interval = service().map(PidsService::checkIntervalTicks).orElse(DEFAULT_INTERVAL_TICKS);
    if (++ticks < interval) {
      return;
    }
    ticks = 0;
    refresh();
  }

  private void reset() {
    written = null;
    target = null;
    pending.clear();
    shownKey = null;
    shownBy = null;
    ticks = 0;
  }

  @Override
  public void onRightClick(MapClickEvent event) {
    click(event, true);
  }

  @Override
  public void onLeftClick(MapClickEvent event) {
    click(event, false);
  }

  /** 控制台上的一次点击：按正显示的那一帧认出点到的东西，交给信号员模块，当拍重画。 */
  private void click(MapClickEvent event, boolean right) {
    if (!(shownKey instanceof PidsComposer.SignalboxKey key)) {
      return;
    }
    Optional<PidsService> service = service();
    if (service.isEmpty() || service.get() != shownBy) {
      return;
    }
    Player player = event.getPlayer();
    if (!clicks.first(player.getUniqueId(), Bukkit.getCurrentTick())) {
      event.setCancelled(true);
      return;
    }
    // 手持配置棍是在配置屏幕，不当按键
    if (service.get().items().isStick(player.getInventory().getItemInMainHand())) {
      return;
    }
    Optional<UUID> screenId =
        PidsFrames.parse(properties.get(PidsFrames.SCREEN_PROPERTY, String.class));
    if (screenId.isEmpty()) {
      return;
    }
    event.setCancelled(true);
    ConsoleTarget target =
        right
            ? service.get().signalboxTarget(key, event.getX(), event.getY())
            : new ConsoleTarget.Deselect();
    if (service.get().pressSignalbox(player, screenId.get(), target)) {
      ticks = 0;
      refresh();
    }
  }

  private void refresh() {
    Optional<PidsService> service = service();
    if (service.isEmpty()) {
      return;
    }
    if (service.get() != shownBy) {
      shownBy = service.get();
      shownKey = null;
    }
    service
        .get()
        .content(
            PidsFrames.parse(properties.get(PidsFrames.SCREEN_PROPERTY, String.class)),
            getWidth(),
            getHeight())
        .ifPresent(content -> show(service.get(), content));
  }

  private void show(PidsService service, PidsContent content) {
    if (content.key().equals(shownKey)) {
      return;
    }
    int width = getWidth();
    int height = getHeight();
    byte[] next = service.frame(content, width, height);
    if (next.length != width * height) {
      return;
    }
    target = next;
    shownKey = content.key();
    pending.clear();
    List<PidsFrameDiff.Region> regions = PidsFrameDiff.changed(written, next, width, height);
    if (written == null) {
      written = new byte[width * height];
    }
    if (PidsFrameDiff.writeTogether(regions)) {
      regions.forEach(this::write);
    } else {
      pending.addAll(regions);
      writeNext();
    }
  }

  private void writeNext() {
    PidsFrameDiff.Region region = pending.poll();
    if (region != null) {
      write(region);
    }
  }

  /** 把目标帧的一个矩形写入画布，并记入已写入的帧。 */
  private void write(PidsFrameDiff.Region region) {
    int width = getWidth();
    getLayer()
        .writePixels(
            region.x(),
            region.y(),
            region.width(),
            region.height(),
            PidsFrameDiff.crop(target, width, region));
    for (int row = 0; row < region.height(); row++) {
      int offset = (region.y() + row) * width + region.x();
      System.arraycopy(target, offset, written, offset, region.width());
    }
  }

  private Optional<PidsService> service() {
    return getPlugin() instanceof FetaruteTCAddon plugin
        ? plugin.getPidsService()
        : Optional.empty();
  }
}
