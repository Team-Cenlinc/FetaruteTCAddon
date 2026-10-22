package org.fetarute.fetaruteTCAddon.coupler;

import com.bergerkiller.bukkit.tc.actions.WaitAction;
import com.bergerkiller.bukkit.tc.controller.MinecartGroup;
import com.bergerkiller.bukkit.tc.controller.MinecartMember;
import com.bergerkiller.bukkit.tc.controller.MinecartMemberStore;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.FetaruteTCAddon;
import org.fetarute.fetaruteTCAddon.config.ConfigManager;
import org.fetarute.fetaruteTCAddon.dispatcher.consist.ConsistKey;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.TrainTagHelper;
import org.fetarute.fetaruteTCAddon.utils.LocaleManager;

/**
 * 车钩扳手的交互：右键端车端部开/锁车钩，右键两节车的连接处在此拆开，Shift+右键开着车钩的端车把整列车推向前方的车并挂上，右键车厢中段看这节车。
 * 手持扳手时每半秒在附近列车外端冒粒子：开着的车钩绿色、锁着的红色（只给手持扳手的玩家看）。
 *
 * <p>驾驶员与车掌值乘中的玩家不处理（驾驶侧会拦下他们的交互）。只在服务器主线程使用。
 */
public final class CouplerWrenchListener implements Listener {

  /** 使用扳手的权限。 */
  public static final String PERMISSION = "fetarute.coupler";

  /** 同一玩家两次点击的最短间隔（tick）：一次右键可能经车厢的几个附件各报一次。 */
  private static final long CLICK_DEBOUNCE_TICKS = 4L;

  /** 粒子提示的间隔（tick）与范围（格）。 */
  private static final long SHOW_PERIOD_TICKS = 10L;

  private static final double SHOW_RADIUS_BLOCKS = 16.0;

  /** 粒子比车厢实体位置高出多少格（车厢实体位置在轨面）。 */
  private static final double SHOW_HEIGHT_BLOCKS = 0.6;

  private static final Particle.DustOptions OPEN_DUST =
      new Particle.DustOptions(Color.fromRGB(0x55FF55), 1.0f);

  private static final Particle.DustOptions CLOSED_DUST =
      new Particle.DustOptions(Color.fromRGB(0xFF5555), 1.0f);

  private final FetaruteTCAddon plugin;
  private final CouplerService service;
  private final WrenchItem wrench;
  private final ShuntPusher pusher;
  private final Map<UUID, Long> lastClick = new HashMap<>();

  /** 玩家刚用扳手开启、前方有车可挂的那一端：聊天栏的“连挂”按钮（{@code /fta coupler couple <令牌>}）照它开过去。 */
  private final Map<UUID, Selection> selections = new HashMap<>();

  private BukkitTask showTask;

  /** 选中的那一端在聊天栏按钮上留多久（tick）。 */
  private static final long SELECTION_TICKS = 1200L;

  /** 点“连挂”按钮时离那列车最远多少格。 */
  private static final double COUPLE_REACH_BLOCKS = 24.0;

  /**
   * 选中要去连挂的那一端。
   *
   * @param token 按钮上的令牌：只认最近一次选中的那个按钮
   * @param member 端车
   * @param end 开着车钩的那一端
   * @param tick 选中时的 tick
   */
  private record Selection(
      String token, MinecartMember<?> member, CouplerState.End end, long tick) {}

  public CouplerWrenchListener(FetaruteTCAddon plugin, CouplerService service, WrenchItem wrench) {
    this.plugin = Objects.requireNonNull(plugin, "plugin");
    this.service = Objects.requireNonNull(service, "service");
    this.wrench = Objects.requireNonNull(wrench, "wrench");
    this.pusher = new ShuntPusher(plugin, service);
  }

  /** 开始粒子提示。 */
  void start() {
    if (showTask == null) {
      showTask =
          Bukkit.getScheduler()
              .runTaskTimer(plugin, this::showCouplers, SHOW_PERIOD_TICKS, SHOW_PERIOD_TICKS);
    }
  }

  /** 停下粒子提示，推着的车就地停下收尾。 */
  void stop() {
    if (showTask != null) {
      showTask.cancel();
      showTask = null;
    }
    pusher.stopAll();
  }

  @EventHandler(priority = EventPriority.LOWEST)
  public void onInteract(PlayerInteractEntityEvent event) {
    Player player = event.getPlayer();
    if (!wrench.isWrench(player.getInventory().getItemInMainHand())) {
      return;
    }
    MinecartMember<?> member = MinecartMemberStore.getFromEntity(event.getRightClicked());
    // 先判车厢还在：刚被移走的车厢取编组 TrainCarts 会抛异常
    if (member == null
        || member.isUnloaded()
        || member.getGroup() == null
        || !member.getGroup().isValid()) {
      return;
    }
    // 拿着扳手点车厢不上车（副手那一次也要拦）。
    event.setCancelled(true);
    if (event.getHand() != EquipmentSlot.HAND) {
      return;
    }
    handleClick(player, member);
  }

  /**
   * 拿着扳手点到方块或空中：扳手本身是绊线钩，不放下去；按 TrainCarts 自己的命中检测（{@link
   * MinecartMemberStore#getFromHitTest}）点中了车厢时按点中那节车处理。 不按 Shift 时 TrainCarts 自己就把这种点击换成点车厢；按住
   * Shift 时它放过不管（好让玩家照常用方块），Shift+右键推车就落到了方块上。
   */
  @EventHandler(priority = EventPriority.LOWEST)
  public void onInteractBlock(PlayerInteractEvent event) {
    if (event.getAction() != Action.RIGHT_CLICK_BLOCK
        && event.getAction() != Action.RIGHT_CLICK_AIR) {
      return;
    }
    Player player = event.getPlayer();
    boolean wrenchInThisHand = wrench.isWrench(event.getItem());
    boolean wrenchInMainHand = wrench.isWrench(player.getInventory().getItemInMainHand());
    if (!wrenchInThisHand && !wrenchInMainHand) {
      return;
    }
    if (wrenchInThisHand) {
      // 扳手不放下去；门、按钮这类方块照常能用，除非点中的是车厢
      event.setUseItemInHand(org.bukkit.event.Event.Result.DENY);
    }
    MinecartMember<?> member = MinecartMemberStore.getFromHitTest(player.getEyeLocation());
    boolean car =
        member != null
            && member.isInteractable()
            && member.getGroup() != null
            && member.getGroup().isValid();
    if (car && wrenchInMainHand) {
      // 点的是车：方块不触发，另一只手的东西也不用
      event.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);
      event.setUseItemInHand(org.bukkit.event.Event.Result.DENY);
    }
    if (event.getAction() == Action.RIGHT_CLICK_BLOCK
        && event.useItemInHand() == org.bukkit.event.Event.Result.DENY) {
      // 客户端已经预先放下、把手里的数量减了一：同步回去
      player.updateInventory();
    }
    if (car && wrenchInMainHand && event.getHand() == EquipmentSlot.HAND) {
      handleClick(player, member);
    }
  }

  /** 拿着扳手不放方块（点到方块的交互已经拦下，这里兜底）。 */
  @EventHandler(priority = EventPriority.LOWEST)
  public void onPlace(BlockPlaceEvent event) {
    if (wrench.isWrench(event.getItemInHand())) {
      event.setCancelled(true);
    }
  }

  /**
   * 聊天栏的“连挂”按钮：让刚用扳手打开车钩的那列车慢慢开向前方的车并挂上（对方相对一端的车钩没开时一并打开）。
   *
   * @param player 点按钮的玩家
   */
  public void coupleSelected(Player player, String token) {
    Selection selection = selections.get(player.getUniqueId());
    if (selection == null || token == null || !selection.token().equals(token)) {
      player.sendMessage(locale().component("coupler.couple.none"));
      return;
    }
    MinecartMember<?> member = selection.member();
    // 先判车厢还在：被删掉的车厢取编组 TrainCarts 会抛异常
    if (Bukkit.getCurrentTick() - selection.tick() > SELECTION_TICKS
        || !member.isInteractable()
        || member.getGroup() == null
        || !member.getGroup().isValid()) {
      selections.remove(player.getUniqueId(), selection);
      player.sendMessage(locale().component("coupler.couple.none"));
      return;
    }
    MinecartGroup group = member.getGroup();
    // 选中以后这一端挂上了别的车、或这节已不是端车：按钮作废
    if (CarEnds.neighbourAt(member, selection.end()).isPresent()
        || (group.head() != member && group.tail() != member)) {
      selections.remove(player.getUniqueId(), selection);
      player.sendMessage(locale().component("coupler.couple.none"));
      return;
    }
    ConfigManager.CouplerSettings settings = service.settings();
    if (!player.hasPermission(PERMISSION) || !settings.enabled()) {
      player.sendMessage(
          locale().component(settings.enabled() ? "coupler.no-permission" : "coupler.disabled"));
      return;
    }
    if (plugin.getDriveSessionManager() != null
        && plugin.getDriveSessionManager().isProtected(player.getUniqueId())) {
      return;
    }
    Vector at = player.getLocation().toVector();
    if (group.getWorld() != player.getWorld()
        || CarEnds.center(member)
            .map(center -> center.distanceSquared(at) > COUPLE_REACH_BLOCKS * COUPLE_REACH_BLOCKS)
            .orElse(true)) {
      player.sendMessage(locale().component("coupler.couple.far"));
      return;
    }
    trace(
        "WRENCH_COUPLE player="
            + player.getName()
            + " train="
            + service.displayName(group)
            + " end="
            + selection.end());
    // 暂时推不了（对方忙、正在跑别的动作等）时选择留着，过一会儿还能再点
    if (push(player, member, selection.end(), settings, true)) {
      selections.remove(player.getUniqueId(), selection);
    }
  }

  /** 拿着扳手点中车厢：按点中的位置开闭车钩、解挂、推车或看信息。 */
  private void handleClick(Player player, MinecartMember<?> member) {
    String clicked =
        "player="
            + player.getName()
            + " train="
            + service.displayName(member.getGroup())
            + " car="
            + (member.getGroup().indexOf(member) + 1)
            + "/"
            + member.getGroup().size()
            + " sneaking="
            + player.isSneaking();
    if (debounced(player)) {
      trace("WRENCH_CLICK " + clicked + " result=debounced");
      return;
    }
    if (plugin.getDriveSessionManager() != null
        && plugin.getDriveSessionManager().isProtected(player.getUniqueId())) {
      trace("WRENCH_CLICK " + clicked + " result=drive-protected");
      return;
    }
    if (!player.hasPermission(PERMISSION)) {
      player.sendMessage(locale().component("coupler.no-permission"));
      return;
    }
    ConfigManager.CouplerSettings settings = service.settings();
    if (!settings.enabled()) {
      player.sendMessage(locale().component("coupler.disabled"));
      return;
    }
    Optional<Vector> center = CarEnds.center(member);
    Optional<Vector> forward = CarEnds.forward(member);
    if (center.isEmpty() || forward.isEmpty()) {
      trace("WRENCH_CLICK " + clicked + " result=no-orientation");
      return;
    }
    Location eye = player.getEyeLocation();
    WrenchAim.Spot spot =
        WrenchAim.spot(
            eye.toVector(),
            eye.getDirection(),
            center.get(),
            forward.get(),
            CarEnds.halfLength(member));
    Optional<CouplerState.End> end = spot.end();
    if (end.isEmpty()) {
      trace("WRENCH_CLICK " + clicked + " result=info");
      showInfo(player, member);
      return;
    }
    Optional<MinecartMember<?>> neighbour = CarEnds.neighbourAt(member, end.get());
    String at = clicked + " end=" + end.get() + " open=" + CouplerState.isOpen(member, end.get());
    if (neighbour.isPresent()) {
      if (player.isSneaking()) {
        trace("WRENCH_CLICK " + at + " result=push-at-joint");
        player.sendMessage(locale().component("coupler.push.joint"));
        return;
      }
      trace("WRENCH_CLICK " + at + " result=split");
      split(player, member, end.get(), neighbour.get());
      return;
    }
    if (player.isSneaking()) {
      trace("WRENCH_CLICK " + at + " result=push");
      push(player, member, end.get(), settings, false);
      return;
    }
    trace("WRENCH_CLICK " + at + " result=toggle");
    toggle(player, member, end.get(), settings);
  }

  @EventHandler
  public void onQuit(PlayerQuitEvent event) {
    lastClick.remove(event.getPlayer().getUniqueId());
    selections.remove(event.getPlayer().getUniqueId());
  }

  private boolean debounced(Player player) {
    long now = Bukkit.getCurrentTick();
    Long last = lastClick.put(player.getUniqueId(), now);
    return last != null && now - last < CLICK_DEBOUNCE_TICKS;
  }

  // ---------------------------------------------------------------- 三种操作

  /** 开/锁端车外端的车钩；打开后这一端前方有车可挂时，在聊天栏给出“连挂”按钮。 */
  private void toggle(
      Player player,
      MinecartMember<?> member,
      CouplerState.End end,
      ConfigManager.CouplerSettings settings) {
    MinecartGroup group = member.getGroup();
    if (pusher.involved(group)) {
      player.sendMessage(locale().component("coupler.busy"));
      return;
    }
    CouplerEligibility.Verdict verdict = service.eligibility(group);
    if (verdict != CouplerEligibility.Verdict.ALLOWED) {
      deny(player, group, verdict);
      return;
    }
    boolean open = !CouplerState.isOpen(member, end);
    // 打上来源编组：列车从此算“用过扳手”，碰撞联挂也要看车钩。
    CarTags.stampOrigins(group);
    CouplerState.setOpen(member, end, open);
    CarEnds.endPoint(member, end)
        .ifPresent(
            point ->
                player.playSound(
                    point.toLocation(group.getWorld()),
                    open ? Sound.BLOCK_IRON_TRAPDOOR_OPEN : Sound.BLOCK_IRON_TRAPDOOR_CLOSE,
                    0.8f,
                    1.2f));
    player.sendMessage(
        locale()
            .component(
                open ? "coupler.toggle.opened" : "coupler.toggle.closed",
                Map.of(
                    "train",
                    service.displayName(group),
                    "car",
                    String.valueOf(group.indexOf(member) + 1),
                    "end",
                    endLabel(group, end))));
    Selection selected = selections.get(player.getUniqueId());
    if (selected != null && selected.member() == member && selected.end() == end) {
      // 锁上（或重新开启）的正是选中的那一端：旧按钮作废
      selections.remove(player.getUniqueId(), selected);
    }
    if (!open) {
      return;
    }
    PushTargetFinder.Search search = PushTargetFinder.find(member, end, settings.pushMaxBlocks());
    if (search.target().isEmpty()) {
      return;
    }
    String token =
        Integer.toHexString(java.util.concurrent.ThreadLocalRandom.current().nextInt(0x1000000));
    selections.put(
        player.getUniqueId(), new Selection(token, member, end, Bukkit.getCurrentTick()));
    player.sendMessage(
        locale()
            .component(
                "coupler.couple.offer",
                Map.of(
                    "train",
                    service.displayName(search.target().get().member().getGroup()),
                    "gap",
                    String.format(Locale.ROOT, "%.1f", search.target().get().gap()),
                    "token",
                    token)));
  }

  /** 在 {@code member} 的 {@code end} 一端（挨着 {@code neighbour}）拆开。 */
  private void split(
      Player player, MinecartMember<?> member, CouplerState.End end, MinecartMember<?> neighbour) {
    MinecartGroup group = member.getGroup();
    if (pusher.involved(group)) {
      player.sendMessage(locale().component("coupler.busy"));
      return;
    }
    Optional<CouplerState.End> neighbourEnd = CarEnds.facingEnd(neighbour, member);
    int at = Math.max(group.indexOf(member), group.indexOf(neighbour));
    String trainName = service.displayName(group);
    CouplerService.SplitAttempt attempt = service.split(group, at);
    if (attempt.verdict() != CouplerEligibility.Verdict.ALLOWED) {
      deny(player, group, attempt.verdict());
      return;
    }
    Optional<TrainSplitter.Result> result = attempt.result();
    TrainSplitter.Outcome outcome =
        result.map(TrainSplitter.Result::outcome).orElse(TrainSplitter.Outcome.NOT_SPLIT);
    if (outcome != TrainSplitter.Outcome.SPLIT && outcome != TrainSplitter.Outcome.RENAME_FAILED) {
      player.sendMessage(locale().component("coupler.split.failed", Map.of("train", trainName)));
      return;
    }
    // 拆开处两端锁上：之后碰上也不会自己挂回去。
    CouplerState.setOpen(member, end, false);
    neighbourEnd.ifPresent(other -> CouplerState.setOpen(neighbour, other, false));
    CarEnds.endPoint(member, end)
        .ifPresent(
            point ->
                player.playSound(
                    point.toLocation(group.getWorld()), Sound.BLOCK_CHAIN_BREAK, 1.0f, 0.8f));
    MinecartGroup kept = result.get().kept().orElse(group);
    int detachedCars = result.get().detached().map(MinecartGroup::size).orElse(0);
    String detachedName =
        result
            .get()
            .detached()
            .filter(MinecartGroup::isValid)
            .map(service::displayName)
            .orElse("-");
    player.sendMessage(
        locale()
            .component(
                outcome == TrainSplitter.Outcome.SPLIT
                    ? "coupler.split.done"
                    : "coupler.split.rename-failed",
                Map.of(
                    "train",
                    trainName,
                    "kept",
                    String.valueOf(kept.size()),
                    "detached",
                    String.valueOf(detachedCars),
                    "name",
                    detachedName)));
  }

  /**
   * 把端车所在列车推向 {@code end} 一侧前方的车并挂上。
   *
   * @param openTarget 对方相对一端的车钩锁着时一并开启（玩家点了“连挂”按钮，就是要挂这两列；没挂上时锁回去）；否则锁着就不推
   * @return 开始推了
   */
  private boolean push(
      Player player,
      MinecartMember<?> member,
      CouplerState.End end,
      ConfigManager.CouplerSettings settings,
      boolean openTarget) {
    MinecartGroup group = member.getGroup();
    String trainName = service.displayName(group);
    if (!CouplerState.isOpen(member, end)) {
      pushRefused(trainName, "closed");
      player.sendMessage(locale().component("coupler.push.closed"));
      return false;
    }
    if (pusher.involved(group)) {
      pushRefused(trainName, "busy");
      player.sendMessage(locale().component("coupler.busy"));
      return false;
    }
    CouplerEligibility.Verdict verdict = service.eligibility(group);
    if (verdict != CouplerEligibility.Verdict.ALLOWED) {
      pushRefused(trainName, "verdict:" + verdict.key());
      deny(player, group, verdict);
      return false;
    }
    // 停站留下的等待动作推车时清掉（没挂上再补回）；别的动作（发车、居中）在跑时不推。
    if (group.getActions().getScheduledActions().stream()
        .anyMatch(action -> !(action instanceof WaitAction))) {
      pushRefused(
          trainName,
          "actions:"
              + group.getActions().getScheduledActions().stream()
                  .map(action -> action.getClass().getSimpleName())
                  .toList());
      player.sendMessage(
          locale().component("coupler.push.actions", Map.of("train", service.displayName(group))));
      return false;
    }
    PushTargetFinder.Search search = PushTargetFinder.find(member, end, settings.pushMaxBlocks());
    if (search.target().isEmpty()) {
      PushTargetFinder.Failure failure =
          search.failure().orElse(PushTargetFinder.Failure.NOT_FOUND);
      pushRefused(trainName, "target:" + failure);
      player.sendMessage(
          locale()
              .component(
                  "coupler.push.target."
                      // 前方区块没加载：玩家看得见的范围里没有车，按没找到说
                      + (failure == PushTargetFinder.Failure.UNLOADED_AHEAD
                              ? PushTargetFinder.Failure.NOT_FOUND
                              : failure)
                          .name()
                          .toLowerCase(Locale.ROOT)
                          .replace('_', '-'),
                  Map.of("blocks", String.valueOf(settings.pushMaxBlocks()))));
      return false;
    }
    PushTargetFinder.Target target = search.target().get();
    MinecartGroup other = target.member().getGroup();
    String otherName = service.displayName(other);
    if (pusher.involved(other)) {
      pushRefused(trainName, "target-busy:" + otherName);
      player.sendMessage(locale().component("coupler.busy"));
      return false;
    }
    CouplerEligibility.Verdict otherVerdict = service.eligibility(other);
    if (otherVerdict != CouplerEligibility.Verdict.ALLOWED) {
      pushRefused(trainName, "target-verdict:" + otherName + ":" + otherVerdict.key());
      deny(player, other, otherVerdict);
      return false;
    }
    if (service.managed(group) != service.managed(other)) {
      pushRefused(trainName, "mixed:" + otherName);
      player.sendMessage(locale().component("coupler.push.mixed"));
      return false;
    }
    if (!CouplerState.isOpen(target.member(), target.end()) && !openTarget) {
      pushRefused(trainName, "target-closed:" + otherName);
      player.sendMessage(
          locale().component("coupler.push.target-closed", Map.of("train", otherName)));
      return false;
    }
    if (service.managed(group) && !service.pushPathGuarded(group, other, target.pathCells())) {
      pushRefused(trainName, "path-unguarded:" + otherName);
      player.sendMessage(locale().component("coupler.push.path-unguarded"));
      return false;
    }
    int cars = group.size() + other.size();
    if (settings.maxCars() > 0 && cars > settings.maxCars()) {
      pushRefused(trainName, "too-long:" + cars);
      player.sendMessage(
          locale()
              .component(
                  "coupler.push.too-long",
                  Map.of("cars", String.valueOf(cars), "max", String.valueOf(settings.maxCars()))));
      return false;
    }
    boolean openedTarget = !CouplerState.isOpen(target.member(), target.end());
    if (openedTarget) {
      CarTags.stampOrigins(other);
      CouplerState.setOpen(target.member(), target.end(), true);
    }
    player.sendMessage(
        locale()
            .component(
                "coupler.push.started",
                Map.of(
                    "train", otherName, "gap", String.format(Locale.ROOT, "%.1f", target.gap()))));
    UUID playerId = player.getUniqueId();
    pusher.start(
        member,
        end,
        target,
        settings.pushSpeedBlocksPerSecond(),
        settings.maxCars(),
        finished -> {
          // 推的途中被 TrainCarts 碰上就挂上了（推车收尾报“列车不在了”）：照挂上告诉玩家
          boolean joined =
              finished.outcome() == ShuntPusher.Outcome.LOST
                  && member.isInteractable()
                  && target.member().isInteractable()
                  && member.getGroup() == target.member().getGroup();
          if (openedTarget
              && finished.outcome() != ShuntPusher.Outcome.LINKED
              && !joined
              && target.member().isInteractable()
              && CarEnds.neighbourAt(target.member(), target.end()).isEmpty()) {
            // 替对方开启的车钩没挂上就锁回去
            CouplerState.setOpen(target.member(), target.end(), false);
          }
          if (joined) {
            reportJoined(playerId, member.getGroup());
          } else {
            report(playerId, otherName, finished);
          }
        });
    return true;
  }

  /** 推的途中两列车已经挂到一起：告诉玩家挂上了。 */
  private void reportJoined(UUID playerId, MinecartGroup merged) {
    trace("WRENCH_PUSH_DONE target=" + service.displayName(merged) + " outcome=JOINED");
    Player player = Bukkit.getPlayer(playerId);
    if (player == null) {
      return;
    }
    player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_LAND, 0.4f, 1.6f);
    player.sendMessage(
        locale()
            .component(
                "coupler.push.linked",
                Map.of(
                    "train", service.displayName(merged), "cars", String.valueOf(merged.size()))));
  }

  private void pushRefused(String trainName, String reason) {
    trace("WRENCH_PUSH_REFUSED train=" + trainName + " reason=" + reason);
  }

  private void trace(String message) {
    if (plugin.getLoggerManager() != null) {
      plugin.getLoggerManager().debug(message);
    }
  }

  /** 推车收尾告诉玩家。 */
  private void report(UUID playerId, String otherName, ShuntPusher.Finished finished) {
    trace("WRENCH_PUSH_DONE target=" + otherName + " outcome=" + finished.outcome());
    Player player = Bukkit.getPlayer(playerId);
    if (player == null) {
      return;
    }
    switch (finished.outcome()) {
      case LINKED -> {
        int cars =
            finished
                .link()
                .flatMap(CouplerService.LinkAttempt::result)
                .flatMap(TrainLinker.Result::merged)
                .map(MinecartGroup::size)
                .orElse(0);
        String survivor =
            finished
                .link()
                .flatMap(CouplerService.LinkAttempt::result)
                .flatMap(TrainLinker.Result::merged)
                .filter(MinecartGroup::isValid)
                .map(service::displayName)
                .orElse(otherName);
        player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_LAND, 0.4f, 1.6f);
        player.sendMessage(
            locale()
                .component(
                    "coupler.push.linked",
                    Map.of("train", survivor, "cars", String.valueOf(cars))));
      }
      case LINK_FAILED -> player.sendMessage(linkFailure(finished.link(), otherName));
      case OVERRUN, STALLED, WRONG_WAY, LOST -> player.sendMessage(
          locale()
              .component(
                  "coupler.push.failed",
                  Map.of(
                      "reason",
                      locale()
                          .text(
                              "coupler.push.reason."
                                  + finished
                                      .outcome()
                                      .name()
                                      .toLowerCase(Locale.ROOT)
                                      .replace('_', '-')))));
    }
  }

  private Component linkFailure(Optional<CouplerService.LinkAttempt> attempt, String otherName) {
    CouplerEligibility.Verdict verdict =
        attempt.map(CouplerService.LinkAttempt::verdict).orElse(CouplerEligibility.Verdict.ALLOWED);
    if (verdict != CouplerEligibility.Verdict.ALLOWED) {
      return locale()
          .component(
              "coupler.push.failed",
              Map.of("reason", locale().text("coupler.deny." + verdict.key())));
    }
    Optional<TrainLinker.Result> result = attempt.flatMap(CouplerService.LinkAttempt::result);
    String reason =
        switch (result.map(TrainLinker.Result::outcome).orElse(TrainLinker.Outcome.INVALID)) {
          case MIXED_OWNERSHIP -> locale().text("coupler.push.reason.mixed");
          case TOO_LONG -> locale().text("coupler.push.reason.too-long");
          case NOT_LINKED -> locale().text("coupler.push.reason.refused")
              + " ("
              + result.map(TrainLinker.Result::detail).orElse("?")
              + ")";
          default -> locale().text("coupler.push.reason.lost");
        };
    return locale().component("coupler.push.failed", Map.of("reason", reason));
  }

  // ---------------------------------------------------------------- 信息与提示

  /** 右键车厢中段：这节车与所在列车。 */
  private void showInfo(Player player, MinecartMember<?> member) {
    MinecartGroup group = member.getGroup();
    String consist =
        TrainTagHelper.readTagValue(group.getProperties(), ConsistKey.TRAIN_TAG).orElse("-");
    player.sendMessage(
        locale()
            .component(
                "coupler.info.header",
                Map.of(
                    "train",
                    service.displayName(group),
                    "car",
                    String.valueOf(group.indexOf(member) + 1),
                    "cars",
                    String.valueOf(group.size()),
                    "consist",
                    consist)));
    player.sendMessage(
        locale()
            .component(
                "coupler.info.couplers",
                Map.of(
                    "front",
                    couplerWord(member, CouplerState.End.FRONT),
                    "rear",
                    couplerWord(member, CouplerState.End.REAR))));
    String state;
    if (!service.managed(group)) {
      state = locale().text("coupler.info.unmanaged");
    } else {
      CouplerEligibility.Verdict verdict = service.eligibility(group);
      state =
          verdict == CouplerEligibility.Verdict.ALLOWED
              ? locale().text("coupler.info.allowed")
              : locale().text("coupler.info.denied")
                  + locale().text("coupler.deny." + verdict.key());
    }
    player.sendMessage(locale().component("coupler.info.state", Map.of("state", state)));
  }

  private String couplerWord(MinecartMember<?> member, CouplerState.End end) {
    Optional<MinecartMember<?>> neighbour = CarEnds.neighbourAt(member, end);
    if (neighbour.isPresent()) {
      MinecartGroup group = member.getGroup();
      return locale()
          .text("coupler.info.coupled")
          .replace("<car>", String.valueOf(group.indexOf(neighbour.get()) + 1));
    }
    return locale()
        .text(CouplerState.isOpen(member, end) ? "coupler.info.open" : "coupler.info.closed");
  }

  /** 端车外端的叫法：多节车写“外侧”，单节车按车厢自身朝向写前端或后端。 */
  private String endLabel(MinecartGroup group, CouplerState.End end) {
    if (group.size() > 1) {
      return locale().text("coupler.end.outer");
    }
    return locale().text(end == CouplerState.End.FRONT ? "coupler.end.front" : "coupler.end.rear");
  }

  private void deny(Player player, MinecartGroup group, CouplerEligibility.Verdict verdict) {
    trace(
        "WRENCH_DENIED player="
            + player.getName()
            + " train="
            + service.displayName(group)
            + " verdict="
            + verdict.key());
    player.sendMessage(
        locale()
            .component(
                "coupler.denied",
                Map.of(
                    "train",
                    service.displayName(group),
                    "reason",
                    locale().text("coupler.deny." + verdict.key()))));
  }

  /** 手持扳手的玩家看附近列车外端的车钩。 */
  private void showCouplers() {
    if (!service.settings().enabled()) {
      return;
    }
    for (Player player : Bukkit.getOnlinePlayers()) {
      if (!wrench.isWrench(player.getInventory().getItemInMainHand())
          || !player.hasPermission(PERMISSION)) {
        continue;
      }
      Location at = player.getLocation();
      for (Entity entity :
          player.getWorld().getNearbyEntities(at, SHOW_RADIUS_BLOCKS, 8.0, SHOW_RADIUS_BLOCKS)) {
        MinecartMember<?> member = MinecartMemberStore.getFromEntity(entity);
        // 先判车厢还在：刚被移走的车厢取编组 TrainCarts 会抛异常
        if (member == null || member.isUnloaded() || member.getGroup() == null) {
          continue;
        }
        for (CouplerState.End end : CouplerState.End.values()) {
          if (CarEnds.neighbourAt(member, end).isPresent()) {
            continue;
          }
          CarEnds.endPoint(member, end)
              .ifPresent(
                  point ->
                      player.spawnParticle(
                          Particle.DUST,
                          point.getX(),
                          point.getY() + SHOW_HEIGHT_BLOCKS,
                          point.getZ(),
                          3,
                          0.05,
                          0.05,
                          0.05,
                          0.0,
                          CouplerState.isOpen(member, end) ? OPEN_DUST : CLOSED_DUST));
        }
      }
    }
  }

  private LocaleManager locale() {
    return plugin.getLocaleManager();
  }
}
