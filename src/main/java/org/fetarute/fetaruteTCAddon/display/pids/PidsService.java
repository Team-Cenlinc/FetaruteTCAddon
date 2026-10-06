package org.fetarute.fetaruteTCAddon.display.pids;

import java.awt.image.BufferedImage;
import java.time.Instant;
import java.time.InstantSource;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.fetarute.fetaruteTCAddon.api.FetaruteApi;
import org.fetarute.fetaruteTCAddon.api.event.TimetableTripAssignedEvent;
import org.fetarute.fetaruteTCAddon.api.event.TimetableTripCancelledEvent;
import org.fetarute.fetaruteTCAddon.api.event.TrainPlatformAssignedEvent;
import org.fetarute.fetaruteTCAddon.api.graph.GraphApi;
import org.fetarute.fetaruteTCAddon.display.pids.announce.PidsAnnouncer;
import org.fetarute.fetaruteTCAddon.display.pids.announce.PidsPlatformChanges;
import org.fetarute.fetaruteTCAddon.display.pids.bulletin.PidsBulletin;
import org.fetarute.fetaruteTCAddon.display.pids.bulletin.PidsBulletinBoard;
import org.fetarute.fetaruteTCAddon.display.pids.layout.PidsLayout;
import org.fetarute.fetaruteTCAddon.display.pids.layout.PidsLayoutRegistry;
import org.fetarute.fetaruteTCAddon.display.pids.map.PidsContent;
import org.fetarute.fetaruteTCAddon.display.pids.map.PidsFrameCache;
import org.fetarute.fetaruteTCAddon.display.pids.map.PidsFrames;
import org.fetarute.fetaruteTCAddon.display.pids.map.PidsMapPalette;
import org.fetarute.fetaruteTCAddon.display.pids.render.PidsBulletinTypesetter;
import org.fetarute.fetaruteTCAddon.display.pids.render.PidsFonts;
import org.fetarute.fetaruteTCAddon.display.pids.render.PidsRenderer;
import org.fetarute.fetaruteTCAddon.display.pids.screen.PidsFacing;
import org.fetarute.fetaruteTCAddon.display.pids.screen.PidsScreen;
import org.fetarute.fetaruteTCAddon.display.pids.screen.PidsScreenRegistry;
import org.fetarute.fetaruteTCAddon.display.pids.screen.PidsWallFinder;
import org.fetarute.fetaruteTCAddon.display.pids.view.ApiPidsDirectory;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsLineStatusViews;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsView;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsViewBuilder;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsVocabulary;
import org.fetarute.fetaruteTCAddon.storage.api.StorageException;
import org.fetarute.fetaruteTCAddon.storage.api.StorageProvider;
import org.fetarute.fetaruteTCAddon.utils.LocaleManager;
import org.fetarute.fetaruteTCAddon.utils.LoggerManager;

/**
 * 站台屏服务：屏幕表、内容、安装与拆除、附近车站识别、管理权限。
 *
 * <p>随插件启动与 {@code /fta reload} 整体重建（公开 API 实例与配置都会换）；地图显示每次现取服务实例，不持有旧的。
 *
 * <p>屏幕表启动时整表读入；读失败时不判定“未注册”、也不清理孤立展示框，避免存储故障时把所有屏幕当孤儿拆掉。
 */
public final class PidsService {

  private static final long DIRECTORY_REFRESH_TICKS = 20L * 60;

  /** 还原未登记屏幕时找展示框的半径；内置最大屏幕为 5 列。 */
  private static final int ORPHAN_REACH = 8;

  /** 安装结果。 */
  public enum Outcome {
    INSTALLED,
    /** 地面或天花板上的展示框。 */
    UNSUPPORTED_FACING,
    /** 墙上凑不出布局尺寸的空展示框矩形。 */
    NO_ROOM,
    /** 附近识别出的车站不归玩家的公司管，或没识别出车站且玩家没有管理权限。 */
    NO_PERMISSION,
    /** 达到 {@code limits.max-screens}。 */
    LIMIT_REACHED,
    STORAGE_UNAVAILABLE,
    /** BKC 配置关闭了展示框地图显示。 */
    MAP_UNAVAILABLE
  }

  /**
   * @param outcome 结果
   * @param screen 安装成功时的屏幕
   */
  public record InstallResult(Outcome outcome, Optional<PidsScreen> screen) {}

  private final JavaPlugin plugin;
  private final PidsSettings settings;
  private final PidsLayoutRegistry layouts;
  private final LoggerManager logger;
  private final StorageProvider storage;
  private final FetaruteApi api;
  private final InstantSource clock = InstantSource.system();
  private final PidsScreenRegistry registry = new PidsScreenRegistry();
  private final PidsBulletinBoard bulletins = new PidsBulletinBoard();
  private final ApiPidsDirectory directory;
  private final PidsSnapshotProvider snapshots;

  /** 最近的站台变更：站台屏的行与站台广播共用。 */
  private final PidsPlatformChanges platformChanges = new PidsPlatformChanges();

  /** 线路运行状况：车次取消或重新绑定时作废其取消缓存。 */
  private final PidsLineStatusProvider lineStatuses;

  private final PidsComposer composer;
  private final PidsItems items;
  private final PidsFrames frames;
  private final PidsAccess access;
  private final PidsAnnouncer announcer;

  /** 换算好的调色板帧，各屏共享；上限约等于 65 块 3×5 统屏或 340 块 1×3 站台屏的整帧。 */
  private final PidsFrameCache frameCache = new PidsFrameCache(16L * 1024 * 1024);

  private final Listener cancellationListener = new CancellationListener();

  /** 本次运行中拆除的屏幕：区块加载时只自动还原这些屏幕的展示框。 */
  private final Set<UUID> removedThisRun = ConcurrentHashMap.newKeySet();

  private volatile boolean loaded;
  private volatile boolean directoryFailing;
  private BukkitTask directoryTask;

  /**
   * @param storage 存储；不可用时为空，此时不加载屏幕、不能安装
   */
  public PidsService(
      JavaPlugin plugin,
      PidsSettings settings,
      PidsLayoutRegistry layouts,
      LocaleManager locale,
      LoggerManager logger,
      Optional<StorageProvider> storage,
      FetaruteApi api) {
    this.plugin = Objects.requireNonNull(plugin, "plugin");
    this.settings = Objects.requireNonNull(settings, "settings");
    this.layouts = Objects.requireNonNull(layouts, "layouts");
    this.logger = Objects.requireNonNull(logger, "logger");
    this.storage = storage.orElse(null);
    this.api = Objects.requireNonNull(api, "api");
    this.directory =
        new ApiPidsDirectory(
            api.operators(), api.lines(), api.stations(), api.routes(), api.graph(), logger::warn);
    this.snapshots =
        new PidsSnapshotProvider(
            api.eta(),
            api.timetables(),
            api.routes(),
            () -> settings,
            clock,
            logger::debug,
            platformChanges);
    this.lineStatuses =
        new PidsLineStatusProvider(api.trains(), api.timetables(), directory, logger::debug);
    this.composer =
        new PidsComposer(
            registry,
            () -> loaded,
            layouts,
            snapshots::snapshot,
            new PidsViewBuilder(directory, new PidsVocabulary(locale::text)),
            directory,
            new PidsRenderer(
                PidsFonts.builtIn(settings.font().cjkGlyphs(), settings.font().detectGlyphs())),
            locale::text,
            PidsService::worldTime,
            () -> settings,
            clock,
            ZoneId.systemDefault(),
            lineStatuses,
            bulletins);
    this.items = new PidsItems(plugin, locale);
    this.frames = new PidsFrames(plugin);
    this.access = new PidsAccess(storage, () -> api.operators().listAllOperators(), logger::warn);
    this.announcer =
        PidsAnnouncer.create(
            plugin,
            registry::all,
            snapshots::snapshot,
            directory,
            locale::text,
            () -> settings,
            clock);
  }

  /**
   * 读入屏幕表并开始定时重建名称目录（异步）。
   *
   * <p>读表失败（含某一行数据无法解析）不抛出：屏幕表保持“未读入”，既不判定未登记，也不还原任何展示框。
   */
  public void start() {
    if (storage == null) {
      logger.warn("存储不可用，站台屏未加载");
    } else {
      try {
        registry.replaceAll(storage.pidsScreens().listAll());
        loaded = true;
        logger.info("站台屏已加载: " + registry.size() + " 块");
      } catch (RuntimeException ex) {
        logger.warn("读取站台屏失败，本次不判定未登记屏幕: " + ex);
      }
      try {
        bulletins.replaceAll(storage.pidsBulletins().listAll());
        if (bulletins.size() > 0) {
          logger.info("站台屏公告已加载: " + bulletins.size() + " 条");
        }
      } catch (RuntimeException ex) {
        logger.warn("读取站台屏公告失败，本次不显示公告: " + ex);
      }
    }
    directoryTask =
        Bukkit.getScheduler()
            .runTaskTimerAsynchronously(
                plugin, this::refreshDirectory, 0L, DIRECTORY_REFRESH_TICKS);
    announcer.start();
    Bukkit.getPluginManager().registerEvents(cancellationListener, plugin);
  }

  /**
   * 接过重载前实例的状态（在 {@link #start()} 之前）：广播的已听记录与最近的站台变更，免得站内玩家重听、屏幕上的站台变更消失。
   *
   * @param previous 重载前的服务（已停止）
   */
  public void continueFrom(PidsService previous) {
    announcer.continueFrom(previous.announcer);
    platformChanges.absorb(previous.platformChanges);
  }

  /** 作废取消行缓存：车次取消、重新绑定或越站之后调用，站台屏、站台广播与线路运行状况屏下一次取数即可看到。 */
  public void invalidateCancellations() {
    snapshots.invalidateCancellations();
    lineStatuses.invalidateCancellations();
  }

  public void stop() {
    if (directoryTask != null) {
      directoryTask.cancel();
      directoryTask = null;
    }
    announcer.stop();
    HandlerList.unregisterAll(cancellationListener);
  }

  /** 重建名称目录；连续失败只在第一次告警，恢复后再失败会再次告警。 */
  private void refreshDirectory() {
    try {
      directory.refresh();
      directoryFailing = false;
    } catch (RuntimeException ex) {
      if (!directoryFailing) {
        logger.warn("站台屏名称目录重建失败，沿用上一份: " + ex);
      }
      directoryFailing = true;
    }
  }

  /** 地图显示的检查间隔。 */
  public int checkIntervalTicks() {
    return settings.render().checkIntervalTicks();
  }

  /** 见 {@link PidsComposer#content}。 */
  public Optional<PidsContent> content(Optional<UUID> screenId, int width, int height) {
    return composer.content(screenId, width, height);
  }

  /**
   * 内容的调色板帧：同一内容在各屏间共享，缓存里没有才渲染、换算。尺寸与要求不符时返回空数组（画面不变）。
   *
   * @param content 内容
   * @param width 宽（像素）
   * @param height 高（像素）
   * @return 只读的帧，长度为 {@code width × height}；渲染尺寸不符时为空数组
   */
  public byte[] frame(PidsContent content, int width, int height) {
    return frameCache.frame(
        content.key(),
        width,
        height,
        () -> {
          BufferedImage image = content.image().get();
          if (image.getWidth() != width || image.getHeight() != height) {
            return new byte[0];
          }
          byte[] out = new byte[width * height];
          PidsMapPalette.minecraft().convert(image, out);
          return out;
        });
  }

  public PidsItems items() {
    return items;
  }

  public PidsLayoutRegistry layouts() {
    return layouts;
  }

  public ApiPidsDirectory directory() {
    return directory;
  }

  public PidsComposer composer() {
    return composer;
  }

  /** 站台广播。 */
  public PidsAnnouncer announcer() {
    return announcer;
  }

  public PidsSettings settings() {
    return settings;
  }

  public Optional<PidsScreen> find(UUID id) {
    return registry.find(id);
  }

  public List<PidsScreen> screens() {
    return registry.all();
  }

  /**
   * 按完整 ID 或 ID 前缀（至少 4 位）找屏幕。
   *
   * @return 匹配的屏幕；多于一块表示前缀不唯一
   */
  public List<PidsScreen> matchIdOrPrefix(String raw) {
    if (raw == null || raw.isBlank()) {
      return List.of();
    }
    String prefix = raw.trim().toLowerCase(Locale.ROOT);
    try {
      return registry.find(UUID.fromString(prefix)).stream().toList();
    } catch (IllegalArgumentException ignored) {
      // 不是完整 UUID，按前缀找
    }
    if (prefix.length() < 4) {
      return List.of();
    }
    return registry.all().stream().filter(s -> s.id().toString().startsWith(prefix)).toList();
  }

  /** 唯一匹配的屏幕（见 {@link #matchIdOrPrefix}）。 */
  public Optional<PidsScreen> findByIdOrPrefix(String raw) {
    List<PidsScreen> matches = matchIdOrPrefix(raw);
    return matches.size() == 1 ? Optional.of(matches.get(0)) : Optional.empty();
  }

  /** 展示框属于哪块屏幕：先查位置索引，再看框里的地图物品（孤立展示框只能靠后者认出）。 */
  public Optional<UUID> screenOf(ItemFrame frame) {
    Optional<UUID> indexed =
        PidsFacing.of(frame.getFacing())
            .flatMap(
                facing ->
                    registry.findByFrame(
                        new PidsScreenRegistry.FrameKey(
                            frame.getWorld().getUID(), PidsFrames.position(frame), facing)))
            .map(PidsScreen::id);
    return indexed.isPresent() ? indexed : PidsFrames.screenIdOf(frame.getItem());
  }

  /**
   * 用安装纸在一面空展示框墙上装屏幕：找矩形、识别附近车站、写库、铺地图。
   *
   * @param player 安装的玩家（用于权限判断）
   * @param clicked 玩家点的展示框
   * @param layout 安装纸上的布局
   */
  public InstallResult install(Player player, ItemFrame clicked, PidsLayout layout) {
    Optional<PidsFacing> facing = PidsFacing.of(clicked.getFacing());
    if (facing.isEmpty()) {
      return new InstallResult(Outcome.UNSUPPORTED_FACING, Optional.empty());
    }
    if (storage == null) {
      return new InstallResult(Outcome.STORAGE_UNAVAILABLE, Optional.empty());
    }
    if (registry.size() >= settings.limits().maxScreens()) {
      return new InstallResult(Outcome.LIMIT_REACHED, Optional.empty());
    }
    UUID worldId = clicked.getWorld().getUID();
    Map<PidsScreen.Position, ItemFrame> wall =
        PidsFrames.wallAround(clicked, Math.max(layout.tileRows(), layout.tileCols()) + 1);
    Optional<PidsScreen.Position> anchor =
        PidsWallFinder.findAnchor(
            PidsFrames.position(clicked),
            facing.get(),
            layout.tileRows(),
            layout.tileCols(),
            position -> {
              ItemFrame frame = wall.get(position);
              return frame != null
                  && PidsFrames.isEmpty(frame)
                  && registry
                      .findByFrame(new PidsScreenRegistry.FrameKey(worldId, position, facing.get()))
                      .isEmpty();
            });
    if (anchor.isEmpty()) {
      return new InstallResult(Outcome.NO_ROOM, Optional.empty());
    }
    PidsScreen.Position center =
        PidsScreen.center(anchor.get(), facing.get(), layout.tileRows(), layout.tileCols());
    List<PidsPlatformNode> nearby =
        nearby(worldId, center).platforms().stream().map(PidsNearby.Platform::node).toList();
    Optional<PidsStationKey> station = nearby.stream().findFirst().map(PidsPlatformNode::station);
    // 附近没有车站（例如挂在大厅里的线路运行状况屏）：能领安装工具即可装，装好后再绑运营商。
    if (station.isPresent() ? !canManage(player, station) : !access.canUseTools(player)) {
      return new InstallResult(Outcome.NO_PERMISSION, Optional.empty());
    }
    Set<String> platforms =
        station
            .map(
                found ->
                    PidsPlatformSelection.nearest(
                        nearby, found, PidsPlatformSelection.limit(layout)))
            .orElse(Set.of());
    UUID id = UUID.randomUUID();
    ItemStack mapItem;
    try {
      mapItem = frames.mapItem(id);
    } catch (RuntimeException ex) {
      logger.warn("无法创建站台屏地图（BKC 是否关闭了展示框地图显示？）: " + ex);
      return new InstallResult(Outcome.MAP_UNAVAILABLE, Optional.empty());
    }
    Instant now = now();
    PidsScreen screen =
        new PidsScreen(
            id,
            worldId,
            anchor.get(),
            facing.get(),
            layout.tileRows(),
            layout.tileCols(),
            layout.id(),
            station,
            platforms,
            Set.of(),
            PidsScreen.Appearance.AUTO,
            PidsScreen.Mode.TEST_CARD,
            now,
            now);
    if (!save(screen)) {
      return new InstallResult(Outcome.STORAGE_UNAVAILABLE, Optional.empty());
    }
    PidsFrames.fill(screen.frames().stream().map(wall::get).toList(), mapItem);
    return new InstallResult(Outcome.INSTALLED, Optional.of(screen));
  }

  /** 写库并更新屏幕表；写库失败时屏幕表不变。 */
  public boolean save(PidsScreen screen) {
    if (storage == null) {
      return false;
    }
    try {
      storage.pidsScreens().save(screen);
    } catch (StorageException ex) {
      logger.warn("保存站台屏失败: " + ex.getMessage());
      return false;
    }
    registry.put(screen);
    return true;
  }

  /** 删库、移出屏幕表，并还原已加载区块里的展示框；未加载的等区块加载时再还原（仅限本次运行中拆除的）。 */
  public boolean remove(PidsScreen screen) {
    if (storage == null) {
      return false;
    }
    try {
      storage.pidsScreens().delete(screen.id());
    } catch (StorageException ex) {
      logger.warn("删除站台屏失败: " + ex.getMessage());
      return false;
    }
    registry.remove(screen.id());
    removedThisRun.add(screen.id());
    PidsFrames.loadedFrames(screen).forEach(PidsFrames::restore);
    return true;
  }

  /**
   * 还原刚加载的区块里、本次运行中已拆除的屏幕的展示框。
   *
   * <p>只认本次运行中拆除的：换了存储后端、回滚了数据库或把世界拷到别的服时，库里查不到的屏幕不一定是拆掉的， 自动摘地图无法挽回。这类展示框显示“未登记”测试卡，由管理员用配置棍手动还原。
   */
  public void cleanOrphans(Collection<? extends Entity> entities) {
    if (removedThisRun.isEmpty()) {
      return;
    }
    for (Entity entity : entities) {
      if (entity instanceof ItemFrame frame) {
        PidsFrames.screenIdOf(frame.getItem())
            .filter(removedThisRun::contains)
            .filter(id -> registry.find(id).isEmpty())
            .ifPresent(id -> PidsFrames.restore(frame));
      }
    }
  }

  /**
   * 立即还原一组未登记的站台屏展示框：以玩家点的那个为中心，取附近放着同一屏幕地图的展示框。
   *
   * @return 还原的个数；屏幕表未读入或这块屏幕仍有登记时为 0
   */
  public int restoreOrphan(ItemFrame clicked) {
    Optional<UUID> id = PidsFrames.screenIdOf(clicked.getItem());
    if (!loaded || id.isEmpty() || registry.find(id.get()).isPresent()) {
      return 0;
    }
    List<ItemFrame> cluster =
        PidsFrames.wallAround(clicked, ORPHAN_REACH).values().stream()
            .filter(frame -> PidsFrames.screenIdOf(frame.getItem()).equals(id))
            .toList();
    cluster.forEach(PidsFrames::restore);
    return cluster.size();
  }

  /** 位置附近的站台节点（来自调度图快照）。 */
  public PidsNearby nearby(UUID worldId, PidsScreen.Position center) {
    return new PidsNearby(
        api.graph()
            .getSnapshot(worldId)
            .map(snapshot -> PidsNearby.of(snapshot.nodes(), center, PidsNearby.RADIUS).platforms())
            .orElse(List.of()));
  }

  /** 调度图里某个车站的全部站台。 */
  public List<String> platformsOf(UUID worldId, PidsStationKey station) {
    return api.graph()
        .getSnapshot(worldId)
        .map(GraphApi.GraphSnapshot::nodes)
        .map(nodes -> PidsNearby.platformsOf(nodes, station))
        .orElse(List.of());
  }

  /** 屏幕所用的布局（不存在时退回同尺寸内置布局）。 */
  public Optional<PidsLayout> layoutOf(PidsScreen screen) {
    return layouts.resolve(screen.layoutId(), screen.tileRows(), screen.tileCols());
  }

  /**
   * 屏幕的线路过滤可选的线路：线路运行状况屏为屏幕所属运营商的线路（绑车站或只绑运营商都行），其余为停靠本站的线路。
   *
   * @return 线路运行状况屏没有运营商、其余屏没绑车站时为空
   */
  public List<PidsView.LineChip> filterableLines(PidsScreen screen) {
    if (isLineStatus(screen)) {
      return screen
          .operatorCode()
          .map(operator -> PidsLineStatusViews.operatorLineChips(directory, operator))
          .orElse(List.of());
    }
    return screen.station().map(directory::linesServing).orElse(List.of());
  }

  /**
   * 线路运行状况屏菜单里可选的运营商：当前的、附近车站的，以及玩家能管理的公司的运营商，按代码排序，最多 {@code limit} 个。
   *
   * @param sender 打开菜单的人
   */
  public List<String> operatorChoices(CommandSender sender, PidsScreen screen, int limit) {
    java.util.LinkedHashSet<String> choices = new java.util.LinkedHashSet<>();
    screen.operatorCode().ifPresent(choices::add);
    nearby(screen.worldId(), screen.center()).stations().stream()
        .map(PidsStationKey::operatorCode)
        .forEach(choices::add);
    choices.addAll(manageableOperators(sender));
    return choices.stream().limit(limit).toList();
  }

  /** 玩家能管理的公司的运营商代码（大写），按代码排序；有管理权限时为全部运营商。 */
  public List<String> manageableOperators(CommandSender sender) {
    Predicate<UUID> manageable = access.manageableCompanies(sender);
    return api.operators().listAllOperators().stream()
        .filter(operator -> manageable.test(operator.companyId()))
        .map(operator -> operator.code().toUpperCase(java.util.Locale.ROOT))
        .sorted()
        .distinct()
        .toList();
  }

  /** 屏幕是线路运行状况屏（布局带状况表组件）：不按站台显示，站台选择不起作用。 */
  public boolean isLineStatus(PidsScreen screen) {
    return layoutOf(screen).flatMap(PidsLayout::lineStatus).isPresent();
  }

  /** 屏幕所用布局对站台数的上限（见 {@link PidsPlatformSelection#limit}）；布局缺失时按车站统屏处理。 */
  public OptionalInt platformLimit(PidsScreen screen) {
    return layoutOf(screen).map(PidsPlatformSelection::limit).orElse(OptionalInt.empty());
  }

  /** 公告表。 */
  public PidsBulletinBoard bulletins() {
    return bulletins;
  }

  /**
   * 写库并更新公告表，站台屏下一次检查即按新内容显示。
   *
   * @return 存储不可用或写库失败时为 false（公告表不变）
   */
  public boolean saveBulletin(PidsBulletin bulletin) {
    if (storage == null) {
      return false;
    }
    try {
      storage.pidsBulletins().save(bulletin);
    } catch (StorageException ex) {
      logger.warn("保存站台屏公告失败: " + ex.getMessage());
      return false;
    }
    bulletins.put(bulletin);
    return true;
  }

  /** 删库并移出公告表，站台屏下一次检查即不再显示。 */
  public boolean removeBulletin(PidsBulletin bulletin) {
    if (storage == null) {
      return false;
    }
    try {
      storage.pidsBulletins().delete(bulletin.id());
    } catch (StorageException ex) {
      logger.warn("删除站台屏公告失败: " + ex.getMessage());
      return false;
    }
    bulletins.remove(bulletin.id());
    return true;
  }

  /**
   * 公告在一种布局上的排版。
   *
   * @param layout 布局
   * @param result 排版结果
   */
  public record BulletinFit(PidsLayout layout, PidsBulletinTypesetter.Result result) {}

  /** 公告在会显示它的布局上各排成几页：取本运营商在用的、会轮播公告的布局；本运营商还没有这样的屏幕时取会轮播公告的内置布局 （不拿别人的自定义布局卡住发布）。按布局编号排序。 */
  public List<BulletinFit> bulletinFits(PidsBulletin bulletin) {
    Map<String, PidsLayout> used = new TreeMap<>();
    for (PidsScreen screen : registry.all()) {
      boolean ours =
          screen.station().map(s -> s.operatorCode().equals(bulletin.operatorCode())).orElse(false);
      if (ours) {
        layoutOf(screen)
            .filter(PidsComposer::showsBulletins)
            .ifPresent(layout -> used.put(layout.id(), layout));
      }
    }
    if (used.isEmpty()) {
      layouts.all().stream()
          .filter(layout -> PidsLayoutRegistry.BUILT_IN.contains(layout.id()))
          .filter(PidsComposer::showsBulletins)
          .forEach(layout -> used.put(layout.id(), layout));
    }
    return used.values().stream()
        .map(layout -> new BulletinFit(layout, composer.typeset(layout, bulletin)))
        .toList();
  }

  /** 见 {@link PidsAccess#canManageCompany}。 */
  public boolean canManageCompany(CommandSender sender, UUID companyId) {
    return access.canManageCompany(sender, companyId);
  }

  /** 见 {@link PidsAccess#manageableCompanies}。 */
  public Predicate<UUID> manageableCompanies(CommandSender sender) {
    return access.manageableCompanies(sender);
  }

  /** 见 {@link PidsAccess#canManage}。 */
  public boolean canManage(CommandSender sender, Optional<PidsStationKey> station) {
    return access.canManage(sender, station);
  }

  /** 见 {@link PidsAccess#canManageOperator}。 */
  public boolean canManageOperator(CommandSender sender, String operatorCode) {
    return access.canManageOperator(sender, Optional.of(operatorCode));
  }

  /** 能否管理这块屏幕（配置、拆除、查看信息）：按屏幕所属运营商判断；车站与运营商都没绑的屏幕，能领安装工具的人都能配置。 */
  public boolean canManage(CommandSender sender, PidsScreen screen) {
    return screen.operatorCode().isPresent()
        ? access.canManageOperator(sender, screen.operatorCode())
        : access.canUseTools(sender);
  }

  /** 见 {@link PidsAccess#canUseTools}。 */
  public boolean canUseTools(CommandSender sender) {
    return access.canUseTools(sender);
  }

  /** 屏幕的修改时刻；SQLite 按毫秒存，截到毫秒以免读回后不相等。 */
  public Instant now() {
    return clock.instant().truncatedTo(ChronoUnit.MILLIS);
  }

  private static OptionalLong worldTime(UUID worldId) {
    World world = Bukkit.getWorld(worldId);
    return world == null ? OptionalLong.empty() : OptionalLong.of(world.getTime());
  }

  /**
   * 公开事件：车次取消或重新绑定（取消之后又有车接上）时作废取消行缓存，站台屏、站台广播与线路运行状况屏的下一次取数即可看到；
   * 站台改了（已定的站台变了，或没能停到计划站台）交给站台广播播报“改在 N 站台”。
   */
  private final class CancellationListener implements Listener {
    @EventHandler(priority = EventPriority.MONITOR)
    public void onCancelled(TimetableTripCancelledEvent event) {
      snapshots.invalidateCancellations();
      lineStatuses.invalidateCancellations();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAssigned(TimetableTripAssignedEvent event) {
      snapshots.invalidateCancellations();
      lineStatuses.invalidateCancellations();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlatform(TrainPlatformAssignedEvent event) {
      Optional<String> from =
          switch (event.getReason()) {
            case CHANGED -> event.getPreviousPlatform();
            case CHANGED_FROM_PLAN -> event.getPlannedPlatform();
            case ASSIGNED -> Optional.empty();
          };
      from.ifPresent(
          previous -> {
            platformChanges.record(
                event.getTrainName(),
                event.getNodeId(),
                previous,
                event.getPlatform(),
                clock.instant());
            snapshots.invalidateSnapshots();
          });
    }
  }
}
