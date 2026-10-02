package org.fetarute.fetaruteTCAddon.display.pids.announce;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.time.ZoneId;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.fetarute.fetaruteTCAddon.display.pids.PidsSettings;
import org.fetarute.fetaruteTCAddon.display.pids.PidsSnapshot;
import org.fetarute.fetaruteTCAddon.display.pids.PidsStationKey;
import org.fetarute.fetaruteTCAddon.display.pids.screen.PidsScreen;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsDirectory;

/**
 * 站台广播：每半秒为站内玩家挑出该听的广播并发送。
 *
 * <ul>
 *   <li>听众：不在乘车（车内由 HUD 负责）、没有关掉广播（{@code /fta announce off}）、离某块已加载站台屏不超过 {@code range-blocks}
 *       的玩家。所在车站取最近那块屏绑定的车站，见 {@link PidsAudience}。
 *   <li>内容：所在车站全部站台的有效广播（{@link PidsAnnouncement#active}），同一车站的快照与站台屏共用缓存， 一次检查里每个车站只算一遍。
 *   <li>通道：进站、通过显示在 ActionBar（站台上的玩家没在乘车，ActionBar 是空的），取消与严重晚点发到聊天； 提示音的声源是来源屏幕。去重与排队见 {@link
 *       PidsAnnouncementLedger}。
 * </ul>
 */
public final class PidsAnnouncer {

  /** 检查周期（ticks）。 */
  static final long PERIOD_TICKS = 10L;

  private static final float SOUND_VOLUME = 0.8f;

  private final JavaPlugin plugin;
  private final Supplier<? extends Collection<PidsScreen>> screens;
  private final Function<PidsStationKey, PidsSnapshot> snapshots;
  private final PidsAnnouncementText text;
  private final Supplier<PidsSettings> settings;
  private final InstantSource clock;
  private final NamespacedKey mutedKey;
  private PidsAnnouncementLedger ledger = new PidsAnnouncementLedger();
  private BukkitTask task;

  /**
   * @param screens 全部屏幕
   * @param snapshots 车站快照（与站台屏共用缓存）
   * @param text 广播文字
   * @param settings 当前 {@code pids.yml} 配置
   * @param clock 时钟
   */
  PidsAnnouncer(
      JavaPlugin plugin,
      Supplier<? extends Collection<PidsScreen>> screens,
      Function<PidsStationKey, PidsSnapshot> snapshots,
      PidsAnnouncementText text,
      Supplier<PidsSettings> settings,
      InstantSource clock) {
    this.plugin = Objects.requireNonNull(plugin, "plugin");
    this.screens = Objects.requireNonNull(screens, "screens");
    this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
    this.text = Objects.requireNonNull(text, "text");
    this.settings = Objects.requireNonNull(settings, "settings");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.mutedKey = new NamespacedKey(plugin, "pids_announce_muted");
  }

  /**
   * 由站台屏服务创建。
   *
   * @param directory 名称目录
   * @param localeText 语言文件原文
   */
  public static PidsAnnouncer create(
      JavaPlugin plugin,
      Supplier<? extends Collection<PidsScreen>> screens,
      Function<PidsStationKey, PidsSnapshot> snapshots,
      PidsDirectory directory,
      Function<String, String> localeText,
      Supplier<PidsSettings> settings,
      InstantSource clock) {
    return new PidsAnnouncer(
        plugin,
        screens,
        snapshots,
        new PidsAnnouncementText(directory, localeText, ZoneId.systemDefault()),
        settings,
        clock);
  }

  public void start() {
    if (task == null) {
      task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, PERIOD_TICKS, PERIOD_TICKS);
    }
  }

  public void stop() {
    if (task != null) {
      task.cancel();
      task = null;
    }
  }

  /**
   * 接过上一个实例的已听记录：{@code /fta reload} 重建站台屏服务时调用（在 {@link #start()} 之前），免得站内玩家把仍有效的广播再听一遍。
   *
   * @param previous 重载前的广播服务（已停止）
   */
  public void continueFrom(PidsAnnouncer previous) {
    this.ledger = previous.ledger;
  }

  /** 玩家是否关掉了站台广播。 */
  public boolean muted(Player player) {
    return player.getPersistentDataContainer().has(mutedKey, PersistentDataType.BYTE);
  }

  /** 开关玩家的站台广播（记在玩家数据里，重进服务器仍有效）。 */
  public void setMuted(Player player, boolean muted) {
    if (muted) {
      player.getPersistentDataContainer().set(mutedKey, PersistentDataType.BYTE, (byte) 1);
    } else {
      player.getPersistentDataContainer().remove(mutedKey);
    }
  }

  private void tick() {
    PidsSettings current = settings.get();
    PidsSettings.BroadcastSettings broadcast = current.broadcast();
    if (!current.enabled() || !broadcast.enabled()) {
      ledger.clear();
      return;
    }
    Instant now = clock.instant();
    Duration remember = Duration.ofSeconds(broadcast.dedupeSeconds());
    Collection<PidsScreen> all = screens.get();
    Map<PidsStationKey, List<PidsAnnouncement>> byStation = new HashMap<>();
    Set<UUID> online = new HashSet<>();
    for (Player player : Bukkit.getOnlinePlayers()) {
      online.add(player.getUniqueId());
      // 不在站内、正在乘车（车内由 HUD 负责）或关掉了广播的玩家也要过一遍账：离站期间的记录才会按时长老化。
      Optional<PidsScreen> source =
          player.isInsideVehicle() || muted(player)
              ? Optional.empty()
              : source(player, all, broadcast);
      List<PidsAnnouncement> active =
          source
              .flatMap(PidsScreen::station)
              .map(
                  station ->
                      byStation.computeIfAbsent(
                          station,
                          key -> PidsAnnouncement.active(snapshots.apply(key), now, broadcast)))
              .orElse(List.of());
      PidsAnnouncementLedger.Delivery delivery =
          ledger.deliver(player.getUniqueId(), active, now, remember);
      if (!delivery.isEmpty() && source.isPresent()) {
        send(player, source.get(), delivery, broadcast);
      }
    }
    ledger.retain(online);
  }

  private static Optional<PidsScreen> source(
      Player player, Collection<PidsScreen> screens, PidsSettings.BroadcastSettings broadcast) {
    Location at = player.getLocation();
    return PidsAudience.source(
        screens,
        player.getWorld().getUID(),
        at.getX(),
        at.getY(),
        at.getZ(),
        broadcast.rangeBlocks(),
        PidsAnnouncer::loaded);
  }

  private void send(
      Player player,
      PidsScreen source,
      PidsAnnouncementLedger.Delivery delivery,
      PidsSettings.BroadcastSettings broadcast) {
    if (broadcast.channelText()) {
      delivery.chat().forEach(announcement -> player.sendMessage(text.render(announcement)));
      if (delivery.overflow() > 0) {
        player.sendMessage(text.overflow(source.station().orElseThrow(), delivery.overflow()));
      }
      delivery
          .actionBar()
          .ifPresent(announcement -> player.sendActionBar(text.render(announcement)));
    }
    if (broadcast.channelSound()) {
      Location at = soundSource(player, source);
      float volume = soundVolume(player.getLocation().distance(at));
      delivery
          .actionBar()
          .ifPresent(
              announcement ->
                  player.playSound(
                      at,
                      announcement.kind() == PidsAnnouncement.Kind.PASSING
                          ? Sound.BLOCK_NOTE_BLOCK_BELL
                          : Sound.BLOCK_NOTE_BLOCK_CHIME,
                      SoundCategory.BLOCKS,
                      volume,
                      1.0f));
      if (!delivery.chat().isEmpty()) {
        player.playSound(at, Sound.BLOCK_NOTE_BLOCK_PLING, SoundCategory.BLOCKS, volume, 0.8f);
      }
    }
  }

  /**
   * 提示音音量：原版客户端里音量不超过 1 的声音 16 格外就听不见，大于 1 只拉长传播距离（16 × 音量）、不会更响。 站内范围可能大于 16
   * 格，所以按距离放大音量，让听到的响度约为一半。
   */
  static float soundVolume(double distance) {
    return (float) Math.max(SOUND_VOLUME, distance / 8.0);
  }

  private static Location soundSource(Player player, PidsScreen screen) {
    PidsScreen.Position center = screen.center();
    return new Location(player.getWorld(), center.x() + 0.5, center.y() + 0.5, center.z() + 0.5);
  }

  /** 屏幕所在区块已加载；为广播加载区块是不允许的。 */
  private static boolean loaded(PidsScreen screen) {
    World world = Bukkit.getWorld(screen.worldId());
    PidsScreen.Position anchor = screen.anchor();
    return world != null && world.isChunkLoaded(anchor.x() >> 4, anchor.z() >> 4);
  }
}
