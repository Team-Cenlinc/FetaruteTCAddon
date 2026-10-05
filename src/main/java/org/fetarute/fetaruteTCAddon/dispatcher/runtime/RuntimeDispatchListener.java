package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import com.bergerkiller.bukkit.tc.SignActionHeader;
import com.bergerkiller.bukkit.tc.controller.MinecartGroup;
import com.bergerkiller.bukkit.tc.controller.MinecartMember;
import com.bergerkiller.bukkit.tc.events.GroupCreateEvent;
import com.bergerkiller.bukkit.tc.events.GroupLinkEvent;
import com.bergerkiller.bukkit.tc.events.GroupRemoveEvent;
import com.bergerkiller.bukkit.tc.events.GroupUnloadEvent;
import com.bergerkiller.bukkit.tc.events.MemberRemoveEvent;
import com.bergerkiller.bukkit.tc.events.SignActionEvent;
import com.bergerkiller.bukkit.tc.properties.TrainProperties;
import com.bergerkiller.bukkit.tc.signactions.SignActionType;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.block.Sign;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.TrainNameNormalizer;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.NodeSignDefinitionParser;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SwitcherSignDefinitionParser;

/**
 * 运行时推进点监听：waypoint/depot/switcher 触发占用判定与下一跳下发。
 *
 * <p>对 MEMBER_ENTER 仅处理车头触发，避免多节车厢重复推进；Waypoint 停站仅在 GROUP_ENTER 触发，避免过早点刹。
 *
 * <p>AutoStation（STATION 类型）节点中，STOP/TERMINATE 由 {@link
 * RuntimeDispatchService#handleStationArrival} 在列车停稳后处理；PASS 不会停稳，因此在牌子触发时即时推进，避免 destination
 * 卡在已通过的站台。
 *
 * <p>列车卸载/移除事件会主动释放占用，防止资源遗留。
 */
public final class RuntimeDispatchListener implements Listener {

  /** 同一对列车的联挂否决证据至少间隔这么久才再写一条；持续叠放时逐次退避到上限。 */
  private static final long LINK_VETO_TRACE_WINDOW_MILLIS = 30_000L;

  private static final long LINK_VETO_TRACE_MAX_WINDOW_MILLIS = 3_600_000L;

  private static final int LINK_VETO_TRACE_MAX_PAIRS = 256;

  /** 出库后多久内被卸载算异常：正常运行的车出库后至少会在原地停留发车授权流程，不会几秒内就掉出加载范围。 */
  private static final long EARLY_UNLOAD_THRESHOLD_MILLIS = 60_000L;

  private final RuntimeDispatchService dispatchService;
  private final Consumer<Runnable> nextTickScheduler;
  private final Consumer<String> diagnostics;
  private final LongSupplier clockMillis;
  private final BiPredicate<UUID, NodeId> ignoredNode;
  private final LinkVetoTraceLimiter linkVetoTraceLimiter =
      new LinkVetoTraceLimiter(
          LINK_VETO_TRACE_WINDOW_MILLIS,
          LINK_VETO_TRACE_MAX_WINDOW_MILLIS,
          LINK_VETO_TRACE_MAX_PAIRS);
  private final DeferredIdentityBatch<MinecartGroup, PendingUnexpectedSplit>
      pendingUnexpectedSplits;

  public RuntimeDispatchListener(RuntimeDispatchService dispatchService) {
    this(dispatchService, bukkitNextTick(), message -> {});
  }

  /**
   * 创建带诊断出口的监听器。
   *
   * @param diagnostics 事件级异常证据的输出端；应接不受观察预算限制的诊断通道
   */
  public static RuntimeDispatchListener withDiagnostics(
      RuntimeDispatchService dispatchService, Consumer<String> diagnostics) {
    return new RuntimeDispatchListener(dispatchService, bukkitNextTick(), diagnostics);
  }

  /**
   * 创建带诊断出口、并按 {@code ignoredNode} 跳过部分节点牌子的监听器。
   *
   * @param ignoredNode 返回 true 的节点牌子不参与推进（如保留旧图期间新放、旧图里没有的节点）
   */
  public static RuntimeDispatchListener withDiagnostics(
      RuntimeDispatchService dispatchService,
      Consumer<String> diagnostics,
      BiPredicate<UUID, NodeId> ignoredNode) {
    return new RuntimeDispatchListener(
        dispatchService, bukkitNextTick(), diagnostics, System::currentTimeMillis, ignoredNode);
  }

  private static Consumer<Runnable> bukkitNextTick() {
    return task ->
        Bukkit.getScheduler()
            .runTask(JavaPlugin.getProvidingPlugin(RuntimeDispatchListener.class), task);
  }

  /**
   * 创建可注入下一 tick 调度器的监听器。
   *
   * <p>该入口仅供同包测试精确推进事件边界；生产环境统一使用 Bukkit 主线程调度器。
   */
  RuntimeDispatchListener(
      RuntimeDispatchService dispatchService, Consumer<Runnable> nextTickScheduler) {
    this(dispatchService, nextTickScheduler, message -> {});
  }

  /** 同包测试入口：同时注入下一 tick 调度器与诊断出口。 */
  RuntimeDispatchListener(
      RuntimeDispatchService dispatchService,
      Consumer<Runnable> nextTickScheduler,
      Consumer<String> diagnostics) {
    this(dispatchService, nextTickScheduler, diagnostics, System::currentTimeMillis);
  }

  /** 同包测试入口：再注入时钟，使证据限流的窗口可精确推进。 */
  RuntimeDispatchListener(
      RuntimeDispatchService dispatchService,
      Consumer<Runnable> nextTickScheduler,
      Consumer<String> diagnostics,
      LongSupplier clockMillis) {
    this(dispatchService, nextTickScheduler, diagnostics, clockMillis, (world, node) -> false);
  }

  /** 同包测试入口：再注入要跳过的节点牌子。 */
  RuntimeDispatchListener(
      RuntimeDispatchService dispatchService,
      Consumer<Runnable> nextTickScheduler,
      Consumer<String> diagnostics,
      LongSupplier clockMillis,
      BiPredicate<UUID, NodeId> ignoredNode) {
    this.dispatchService = dispatchService;
    this.nextTickScheduler = nextTickScheduler;
    this.diagnostics = diagnostics != null ? diagnostics : message -> {};
    this.clockMillis = clockMillis;
    this.ignoredNode = ignoredNode != null ? ignoredNode : (world, node) -> false;
    this.pendingUnexpectedSplits =
        new DeferredIdentityBatch<>(nextTickScheduler, this::classifyUnexpectedSplit);
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onSignAction(SignActionEvent event) {
    if (event == null) {
      return;
    }
    SignActionType action = event.getAction();
    if (action != SignActionType.GROUP_ENTER && action != SignActionType.MEMBER_ENTER) {
      return;
    }
    if (action == SignActionType.MEMBER_ENTER && !isHeadMember(event)) {
      return;
    }
    if (!event.hasGroup()) {
      return;
    }
    Optional<SignNodeDefinition> definitionOpt = resolveDefinition(event);
    if (definitionOpt.isEmpty()) {
      return;
    }
    SignNodeDefinition definition = definitionOpt.get();
    // 保留旧图期间新放的节点牌子不在旧图里：推进到它只会让信号起点回退、占用释放停摆，等重建后再纳入。
    if (event.getWorld() != null
        && ignoredNode.test(event.getWorld().getUID(), definition.nodeId())) {
      return;
    }
    // 车头先于编组进入牌子时，已声明的普通经过点必须立即推进；STOP/TERM 与未声明中间点仍等待原有边界。
    if (action == SignActionType.MEMBER_ENTER && definition.nodeType() == NodeType.WAYPOINT) {
      dispatchService.handleWaypointMemberEnter(event, definition);
      return;
    }
    // STATION STOP/TERM 由 AutoStation.handleStop() -> handleStationArrival() 在列车停稳后推进，
    // 但 PASS 站不会停稳，必须在经过牌子时推进，否则 destination 会卡在被通过的站。
    if (definition.nodeType() == NodeType.STATION) {
      if (shouldHandleStationPassProgress(event)
          && dispatchService.shouldAdvancePassedStation(
              event.getGroup().getProperties(), definition)) {
        dispatchService.handleProgressTrigger(event, definition);
      }
      return;
    }
    dispatchService.handleProgressTrigger(event, definition);
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onGroupLink(GroupLinkEvent event) {
    if (event == null) {
      return;
    }
    MinecartGroup first = event.getGroup1();
    MinecartGroup second = event.getGroup2();
    dispatchService.beginExpectedPhysicalTopologyRecovery(
        identityDistinctMatching(List.of(first, second), group -> group != null).stream()
            .map(TrainCartsRuntimeHandle::new)
            .toList(),
        "group-link");
  }

  /**
   * 否决受管列车之间的跨属主联挂。
   *
   * <p>优先级高于监听联挂的 MONITOR 处理器（后者 {@code ignoreCancelled}），被否决的联挂不会触发全局 STOP_FIRST 恢复。
   */
  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void onGroupLinkVeto(GroupLinkEvent event) {
    if (event == null || event.getGroup1() == null || event.getGroup2() == null) {
      return;
    }
    vetoLinkIfCrossOwner(
        new TrainCartsRuntimeHandle(event.getGroup1()),
        new TrainCartsRuntimeHandle(event.getGroup2()),
        event::setCancelled);
  }

  /**
   * 跨属主则通过 {@code cancel} 否决联挂；证据按列车对限流（被否决的联挂不会完成，叠着的编组每个物理 tick 都会再撞）。
   *
   * <p>取消永远执行，限流只作用于日志。
   */
  void vetoLinkIfCrossOwner(
      RuntimeTrainHandle first, RuntimeTrainHandle second, Consumer<Boolean> cancel) {
    boolean veto;
    try {
      veto = shouldVetoGroupLink(first, second);
    } catch (RuntimeException | LinkageError ex) {
      // 判定失败保持 TrainCarts 原行为：默认取消会误伤玩家自己的车厢。同样的错误每个物理 tick 都会重现，走限流。
      String errorKey = "error|" + ex.getClass().getName();
      if (linkVetoTraceLimiter.admit(errorKey, clockMillis.getAsLong()).isPresent()) {
        diagnostics.accept(
            "SMART_GROUP_LINK_VETO_ERROR error="
                + ex.getClass().getSimpleName()
                + ":"
                + ex.getMessage());
      }
      return;
    }
    if (!veto) {
      return;
    }
    cancel.accept(Boolean.TRUE);
    String firstSide = describeLinkSide(first);
    String secondSide = describeLinkSide(second);
    String pairKey =
        firstSide.compareTo(secondSide) <= 0
            ? firstSide + "|" + secondSide
            : secondSide + "|" + firstSide;
    OptionalInt suppressed = linkVetoTraceLimiter.admit(pairKey, clockMillis.getAsLong());
    if (suppressed.isPresent()) {
      diagnostics.accept(
          "SMART_GROUP_LINK_VETOED first="
              + firstSide
              + " second="
              + secondSide
              + " suppressed="
              + suppressed.getAsInt());
    }
  }

  private static String describeLinkSide(RuntimeTrainHandle train) {
    TrainProperties properties = train == null ? null : train.properties();
    if (properties == null) {
      return "-";
    }
    String tagOwner =
        TrainTagHelper.readTagValue(properties, RouteProgressRegistry.TAG_TRAIN_NAME).orElse("-");
    return properties.getTrainName() + "(tagOwner=" + tagOwner + ")";
  }

  /**
   * 两个受管列车是否会被联挂成同一个编组——是则否决。
   *
   * <p>只有两侧都带 FTA 标签才可能否决；任何一侧不是受管列车（玩家自己的车厢等）保持 TrainCarts 原行为。
   *
   * <p>同一列车要求 {@code FTA_TRAIN_NAME} 标签与 TrainCarts 名<b>两个维度</b>都一致（各自归一化，TC 拆分别名 {@code ~a} 视为同名）。
   * 这里刻意不用运行时的 {@code resolveTrackedTrainName}：它在两者不一致时改以 TC 名为准，会把 tag=DS、名=MT~k 的车判成与 MT
   * 同属主而放行联挂。
   */
  boolean shouldVetoGroupLink(RuntimeTrainHandle first, RuntimeTrainHandle second) {
    Optional<OwnerIdentity> firstOwner = managedOwner(first);
    Optional<OwnerIdentity> secondOwner = managedOwner(second);
    if (firstOwner.isEmpty() || secondOwner.isEmpty()) {
      return false;
    }
    // 认不出属主无法证明是同一列车，与“属主不同”同样处理。
    return !firstOwner.get().knownSameAs(secondOwner.get());
  }

  /** 受管列车的逻辑身份：标签属主与 TrainCarts 名，均已归一化；缺标签时标签属主取名字。 */
  private record OwnerIdentity(String tagKey, String nameKey) {
    boolean knownSameAs(OwnerIdentity other) {
      return !nameKey.isEmpty()
          && !tagKey.isEmpty()
          && tagKey.equals(other.tagKey)
          && nameKey.equals(other.nameKey);
    }
  }

  /** 非受管列车为空；受管列车即使认不出属主也返回身份（键为空字符串）。 */
  private Optional<OwnerIdentity> managedOwner(RuntimeTrainHandle train) {
    TrainProperties properties = train == null ? null : train.properties();
    if (properties == null || !dispatchService.hasFtaRuntimeTag(properties)) {
      return Optional.empty();
    }
    String nameKey = TrainNameNormalizer.normalizeKey(properties.getTrainName());
    String tagKey =
        TrainTagHelper.readTagValue(properties, RouteProgressRegistry.TAG_TRAIN_NAME)
            .map(TrainNameNormalizer::normalizeKey)
            .orElse(nameKey);
    return Optional.of(new OwnerIdentity(tagKey, nameKey));
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void onGroupCreate(GroupCreateEvent event) {
    MinecartGroup group = event != null ? event.getGroup() : null;
    if (group == null) {
      return;
    }
    TrainCartsRuntimeHandle handle = new TrainCartsRuntimeHandle(group);
    handleGroupCreate(handle, () -> refreshGroup(group));
  }

  /**
   * 处理已适配的 GroupCreate 物理身份。
   *
   * <p>把 TrainCarts 事件对象适配留在入口处，保证 tombstone 收容、继承标签硬停与下一 tick 刷新能在不初始化 TrainCarts 静态类型的单测中验证。
   * 真实事件仍由 {@link #onGroupCreate(GroupCreateEvent)} 传入精确 {@link TrainCartsRuntimeHandle}。
   *
   * @param handle 刚创建的精确物理列车句柄
   * @param deferredRefresh 下一 tick 的完整信号刷新动作
   */
  void handleGroupCreate(RuntimeTrainHandle handle, Runnable deferredRefresh) {
    if (handle == null) {
      return;
    }
    if (!dispatchService.containMaterializedSpawnRollbackOnCreate(handle)) {
      stopInheritedFtaGroupUntilCreateCompletes(handle);
    }
    if (nextTickScheduler != null && deferredRefresh != null) {
      nextTickScheduler.accept(deferredRefresh);
    }
  }

  /**
   * 把 GroupCreate 的属性读取推迟到下一 tick。
   *
   * <p>TrainCarts 会在 {@code SpawnableGroup#spawn} 尚未返回、正式 owner/route/index 尚未写入时同步发布
   * GroupCreate。当前 tick 读取模板继承标签会把插件自己的 Depot spawn 误判为迟加载列车，并在发车事务中途关闭全局授权门。
   */
  static <T> void deferGroupCreateRefresh(
      Consumer<Runnable> nextTickScheduler, T group, Consumer<T> refresh) {
    if (nextTickScheduler == null || group == null || refresh == null) {
      return;
    }
    nextTickScheduler.accept(() -> refresh.accept(group));
  }

  /** GroupCreate 同步窗口内只冻结继承了 FTA 标签的编组，不读取半初始化路线语义。 */
  private void stopInheritedFtaGroupUntilCreateCompletes(RuntimeTrainHandle train) {
    try {
      if (train.properties() == null || !dispatchService.hasFtaRuntimeTag(train.properties())) {
        return;
      }
      train.stopHard();
    } catch (RuntimeException | LinkageError ignored) {
      // 下一 tick 的统一 signal/recovery 入口仍会 fail-closed；事件回调本身不能被兼容性错误杀死。
    }
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void onGroupUnload(GroupUnloadEvent event) {
    handleGroupUnloaded(event != null ? event.getGroup() : null);
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void onGroupRemove(GroupRemoveEvent event) {
    handleGroupRemoved(event != null ? event.getGroup() : null);
  }

  /**
   * 编组拆分/脱挂兜底：聚合同 tick 的成员移除，并在下一 tick 确认是否仍有残编存活。
   *
   * <p>TrainCarts 整组销毁时会先为每节车厢触发 {@link MemberRemoveEvent}，最后才触发组移除事件；立即清理会把正常销毁误报为 split，甚至递归销毁。
   * 因此 FTA 成员事件当下只冻结相关物理实例并撤销旧授权，不释放 claim、不清状态也不销毁；若同 tick 收到组移除/卸载则取消候选并走正常精确清理，仅在下一 tick
   * 仍存在源编组或拆分残编时才按异常处理。
   */
  @EventHandler(priority = EventPriority.MONITOR)
  public void onMemberRemove(MemberRemoveEvent event) {
    if (event == null) {
      return;
    }
    MinecartMember<?> member = event.getMember();
    MinecartGroup sourceGroup = event.getGroup();
    MinecartGroup detachedGroup = member != null ? member.getGroup() : null;
    boolean sourceFta = hasFtaRuntimeTag(sourceGroup);
    boolean detachedFta = hasFtaRuntimeTag(detachedGroup);
    boolean sourceDerailed = RuntimeSignalMonitor.isDerailed(sourceGroup);
    boolean detachedDerailed = RuntimeSignalMonitor.isDerailed(detachedGroup);
    if (!sourceFta && !detachedFta && !sourceDerailed && !detachedDerailed) {
      return;
    }
    if (sourceFta || detachedFta) {
      List<MinecartGroup> potentialPhysicalGroups = new ArrayList<>();
      potentialPhysicalGroups.add(sourceGroup);
      potentialPhysicalGroups.add(detachedGroup);
      dispatchService.containPotentialFtaPhysicalChange(
          identityDistinctMatching(potentialPhysicalGroups, group -> group != null).stream()
              .map(TrainCartsRuntimeHandle::new)
              .toList(),
          "member-remove");
    }
    queueUnexpectedSplitCheck(
        sourceGroup,
        member,
        buildUnexpectedSplitDetail(sourceGroup, detachedGroup, member),
        sourceFta || detachedFta);
  }

  private boolean hasFtaRuntimeTag(MinecartGroup group) {
    return group != null
        && group.getProperties() != null
        && dispatchService.hasFtaRuntimeTag(group.getProperties());
  }

  private void handleGroupRemoved(MinecartGroup group) {
    pendingUnexpectedSplits.cancel(group);
    if (group == null) {
      return;
    }
    dispatchService.handleTrainRemoved(new TrainCartsRuntimeHandle(group));
  }

  private void queueUnexpectedSplitCheck(
      MinecartGroup sourceGroup,
      MinecartMember<?> member,
      String splitDetail,
      boolean ftaRuntimeTagged) {
    if (sourceGroup == null) {
      return;
    }
    pendingUnexpectedSplits.add(
        sourceGroup,
        () -> new PendingUnexpectedSplit(sourceGroup, member, splitDetail, ftaRuntimeTagged),
        pending -> pending.add(member));
  }

  private void classifyUnexpectedSplit(PendingUnexpectedSplit pending) {
    SplitSurvivors<MinecartGroup> survivors = pending.survivingGroups();
    String detail = pending.detail(survivors.unresolvedMembers());
    for (MinecartGroup survivingGroup : survivors.groups()) {
      dispatchService.handleAbnormalGroup(
          survivingGroup, pending.reasonFor(survivingGroup), detail);
    }
  }

  private void refreshGroup(MinecartGroup group) {
    if (group == null || !group.isValid()) {
      return;
    }
    TrainCartsRuntimeHandle handle = new TrainCartsRuntimeHandle(group);
    refreshCreatedGroup(
        handle,
        () ->
            dispatchService.handleAbnormalGroup(
                group, "group-create-materialized-rollback-pending"),
        () -> dispatchService.handleSignalTick(group));
  }

  /**
   * 在 GroupCreate 延迟边界上区分当前 Depot 事务墓碑与孤立回滚墓碑。
   *
   * <p>正常 provisional 编组必须继续信号水合；只有无法由当前进程的精确 expected/hydrated identity
   * 解释的持久墓碑才进入异常收容。该分支保留为句柄级入口，避免测试初始化 TrainCarts 静态类型。
   *
   * @param handle 下一 tick 仍有效的精确物理编组
   * @param orphanedTombstoneContainment 孤立墓碑的异常收容动作
   * @param signalRefresh 当前事务或普通编组的完整信号刷新动作
   */
  void refreshCreatedGroup(
      RuntimeTrainHandle handle, Runnable orphanedTombstoneContainment, Runnable signalRefresh) {
    if (handle == null || !handle.isValid()) {
      return;
    }
    if (dispatchService.resumeMaterializedSpawnRollback(handle)) {
      return;
    }
    TrainProperties properties = handle.properties();
    if (properties != null && dispatchService.hasMaterializedSpawnRollbackTag(properties)) {
      if (dispatchService.isCurrentMaterializedSpawnTransactionIdentity(handle)) {
        if (signalRefresh != null) {
          signalRefresh.run();
        }
        return;
      }
      if (orphanedTombstoneContainment != null) {
        orphanedTombstoneContainment.run();
      }
      return;
    }
    if (signalRefresh != null) {
      signalRefresh.run();
    }
  }

  private void handleGroupUnloaded(MinecartGroup group) {
    pendingUnexpectedSplits.cancel(group);
    if (group == null) {
      return;
    }
    TrainCartsRuntimeHandle handle = new TrainCartsRuntimeHandle(group);
    traceEarlyUnload(handle);
    notifyGroupUnloaded(dispatchService, handle);
  }

  /**
   * 出库不久就被卸载时留下证据。
   *
   * <p>卸载会被运行时当作移除处理（占用随即释放），而冻结在出库口的车体仍在离线存储里：下一班在同一锚点生成，苏醒时同坐标复原并被联挂。这里留下的证据用于定位这类现场。
   */
  void traceEarlyUnload(RuntimeTrainHandle train) {
    try {
      TrainProperties properties = train == null ? null : train.properties();
      if (properties == null || !dispatchService.hasFtaRuntimeTag(properties)) {
        return;
      }
      Optional<Long> runAt = TrainTagHelper.readLongTag(properties, "FTA_RUN_AT");
      if (runAt.isEmpty()) {
        return;
      }
      long ageMillis = clockMillis.getAsLong() - runAt.get();
      if (ageMillis < 0L || ageMillis >= EARLY_UNLOAD_THRESHOLD_MILLIS) {
        return;
      }
      diagnostics.accept(
          "SMART_FTA_EARLY_UNLOAD train="
              + properties.getTrainName()
              + " ageMs="
              + ageMillis
              + " keepChunksLoaded="
              + properties.isKeepingChunksLoaded());
    } catch (RuntimeException | LinkageError ignored) {
      // 证据是尽力而为，读取失败不能影响卸载事件本身的处理。
    }
  }

  /**
   * 向运行时传递 GroupUnload 的句柄边界。
   *
   * <p>Unload 只表示实体暂时离线，不得被当作 GroupRemove；该方法故意不触碰移除确认或 occupancy release。
   */
  static void notifyGroupUnloaded(
      RuntimeDispatchService dispatchService, RuntimeTrainHandle train) {
    if (dispatchService != null && train != null) {
      dispatchService.handleTrainUnloaded(train);
    }
  }

  private Optional<SignNodeDefinition> resolveDefinition(SignActionEvent event) {
    if (event == null) {
      return Optional.empty();
    }
    if (event.getTrackedSign() != null) {
      Optional<SignNodeDefinition> switcher =
          SwitcherSignDefinitionParser.parse(event.getTrackedSign());
      if (switcher.isPresent()) {
        return switcher;
      }
      return NodeSignDefinitionParser.parse(event.getTrackedSign());
    }
    Sign sign = event.getSign();
    Optional<SignNodeDefinition> switcher = SwitcherSignDefinitionParser.parse(sign);
    if (switcher.isPresent()) {
      return switcher;
    }
    return NodeSignDefinitionParser.parse(sign);
  }

  private boolean isHeadMember(SignActionEvent event) {
    if (!event.hasGroup()) {
      return false;
    }
    MinecartGroup group = event.getGroup();
    if (group == null) {
      return false;
    }
    MinecartMember<?> member = event.getMember();
    if (member == null) {
      return false;
    }
    return group.head() == member;
  }

  /**
   * 判断 AutoStation PASS 是否应由运行时监听器推进。
   *
   * <p>{@code [train]} 牌子使用 GROUP_ENTER；{@code [cart]} 牌子没有 GROUP_ENTER，只处理车头 MEMBER_ENTER。
   */
  private boolean shouldHandleStationPassProgress(SignActionEvent event) {
    if (event == null) {
      return false;
    }
    SignActionType action = event.getAction();
    if (action == SignActionType.GROUP_ENTER) {
      return true;
    }
    if (action != SignActionType.MEMBER_ENTER) {
      return false;
    }
    SignActionHeader header =
        event.getTrackedSign() != null ? event.getTrackedSign().getHeader() : null;
    if (header == null) {
      header = SignActionHeader.parse(event.getLine(0));
    }
    return shouldHandleStationPassProgress(action, header != null && header.isCart());
  }

  /**
   * 判断 AutoStation PASS 是否应在当前触发类型下推进。
   *
   * <p>该纯判定用于隔离 TrainCarts 事件对象，确保测试可直接覆盖 `[train]` 与 `[cart]` 牌子的触发差异。
   */
  static boolean shouldHandleStationPassProgress(SignActionType action, boolean cartHeader) {
    if (action == SignActionType.GROUP_ENTER) {
      return true;
    }
    return action == SignActionType.MEMBER_ENTER && cartHeader;
  }

  private String buildUnexpectedSplitDetail(
      MinecartGroup sourceGroup, MinecartGroup detachedGroup, MinecartMember<?> member) {
    StringBuilder builder = new StringBuilder();
    RuntimeDiagnosticFormatter.appendKeyValue(builder, "removedMemberPos", describeMember(member));
    RuntimeDiagnosticFormatter.appendKeyValue(
        builder, "sourceTrain", describeGroupName(sourceGroup));
    if (detachedGroup != null && detachedGroup != sourceGroup) {
      RuntimeDiagnosticFormatter.appendKeyValue(
          builder, "detachedTrain", describeGroupName(detachedGroup));
    }
    return builder.length() == 0 ? null : builder.toString();
  }

  private String describeGroupName(MinecartGroup group) {
    if (group == null) {
      return null;
    }
    TrainProperties properties = group.getProperties();
    if (properties == null) {
      return null;
    }
    return dispatchService
        .resolveTrackedTrainName(properties)
        .orElseGet(
            () -> {
              String rawName = properties.getTrainName();
              return rawName == null || rawName.isBlank() ? null : rawName.trim();
            });
  }

  private static String describeMember(MinecartMember<?> member) {
    if (member == null) {
      return null;
    }
    org.bukkit.block.Block block = member.getBlock(0, 0, 0);
    return block != null ? RuntimeDiagnosticFormatter.formatLocation(block.getLocation()) : null;
  }

  /** 按对象身份保留所有满足条件的候选，避免内容相等或可变 hash 把不同残编误合并。 */
  static <T> List<T> identityDistinctMatching(List<T> candidates, Predicate<T> predicate) {
    Map<T, Boolean> seen = new IdentityHashMap<>();
    List<T> matches = new ArrayList<>();
    if (candidates == null || predicate == null) {
      return List.of();
    }
    for (T candidate : candidates) {
      if (candidate == null || seen.containsKey(candidate) || !predicate.test(candidate)) {
        continue;
      }
      seen.put(candidate, Boolean.TRUE);
      matches.add(candidate);
    }
    return List.copyOf(matches);
  }

  /**
   * 拆分候选里仍存活的编组：源编组，加上各被移除成员此刻所在的编组，按对象身份去重。
   *
   * <p>查不到编组的成员（实体已死、已卸载，或查询抛异常）不贡献残编、只计数，源编组照常交付。分类一旦整体失败，异常编组就进不了清理，
   * 成员移除时装上的隔离再也不会收尾，全局现场重建每秒因隔离失败重试，全网随之冻结。典型触发是一节车厢实体死亡后， {@code getGroup()} 想给它新建编组时抛异常。
   *
   * @param source 源编组
   * @param removedMembers 同一 tick 内被移除的成员
   * @param currentGroup 成员此刻所在的编组；没有时为空
   * @param alive 编组是否仍存活
   * @return 存活编组与查不到编组的成员数
   */
  static <G, M> SplitSurvivors<G> survivingGroups(
      G source, List<M> removedMembers, Function<M, Optional<G>> currentGroup, Predicate<G> alive) {
    List<G> candidates = new ArrayList<>();
    candidates.add(source);
    int unresolved = 0;
    for (M member : removedMembers) {
      Optional<G> group;
      try {
        group = currentGroup.apply(member);
      } catch (RuntimeException ex) {
        group = Optional.empty();
      }
      if (group.isPresent()) {
        candidates.add(group.get());
      } else {
        unresolved++;
      }
    }
    return new SplitSurvivors<>(identityDistinctMatching(candidates, alive), unresolved);
  }

  /**
   * 拆分分类的结果。
   *
   * @param groups 仍存活的编组（源编组在前）
   * @param unresolvedMembers 查不到当前编组的被移除成员数
   */
  record SplitSurvivors<G>(List<G> groups, int unresolvedMembers) {}

  /** 同一源编组在一个 tick 内产生的成员移除候选；以对象身份聚合，避免可变编组的 {@code hashCode} 失稳。 */
  private static final class PendingUnexpectedSplit {

    private final MinecartGroup sourceGroup;
    private final List<MinecartMember<?>> removedMembers = new ArrayList<>();
    private final String firstDetail;
    private final boolean ftaRuntimeTagged;

    private PendingUnexpectedSplit(
        MinecartGroup sourceGroup,
        MinecartMember<?> member,
        String firstDetail,
        boolean ftaRuntimeTagged) {
      this.sourceGroup = sourceGroup;
      this.firstDetail = firstDetail;
      this.ftaRuntimeTagged = ftaRuntimeTagged;
      add(member);
    }

    private String reasonFor(MinecartGroup survivingGroup) {
      if (!ftaRuntimeTagged) {
        return "status-derailed";
      }
      return survivingGroup == sourceGroup
          ? "unexpected-split-source"
          : "unexpected-split-detached";
    }

    private void add(MinecartMember<?> member) {
      if (member != null) {
        removedMembers.add(member);
      }
    }

    private SplitSurvivors<MinecartGroup> survivingGroups() {
      return RuntimeDispatchListener.survivingGroups(
          sourceGroup,
          removedMembers,
          PendingUnexpectedSplit::currentGroup,
          PendingUnexpectedSplit::isAlive);
    }

    /**
     * 成员此刻所在的编组。
     *
     * <p>不能直接调 {@code getGroup()}：成员没有编组时 TrainCarts 会给它新建一个，实体已死时直接抛异常。
     */
    private static Optional<MinecartGroup> currentGroup(MinecartMember<?> member) {
      return member.hasInitializedGroup() ? Optional.of(member.getGroup()) : Optional.empty();
    }

    private String detail(int unresolvedMembers) {
      StringBuilder builder = new StringBuilder();
      RuntimeDiagnosticFormatter.appendKeyValue(
          builder, "memberRemovalCount", Integer.toString(removedMembers.size()));
      if (unresolvedMembers > 0) {
        RuntimeDiagnosticFormatter.appendKeyValue(
            builder, "unresolvedMembers", Integer.toString(unresolvedMembers));
      }
      RuntimeDiagnosticFormatter.appendKeyValue(builder, "firstRemoval", firstDetail);
      return builder.length() == 0 ? null : builder.toString();
    }

    private static boolean isAlive(MinecartGroup group) {
      return group != null && group.isValid() && group.getProperties() != null;
    }
  }

  /**
   * 按对象身份聚合同一 tick 的候选事件，并在下一 tick 至多交付一次。
   *
   * <p>键可能是内容持续变化的 TrainCarts 编组，因此必须使用 identity 语义；取消后旧任务即使稍后执行，也不能误消费同一对象的新一批候选。
   */
  static final class DeferredIdentityBatch<K, V> {

    private final Map<K, V> pending = new IdentityHashMap<>();
    private final Consumer<Runnable> nextTickScheduler;
    private final Consumer<V> processor;

    DeferredIdentityBatch(Consumer<Runnable> nextTickScheduler, Consumer<V> processor) {
      this.nextTickScheduler = nextTickScheduler;
      this.processor = processor;
    }

    void add(K key, Supplier<V> initializer, Consumer<V> aggregator) {
      V current = pending.get(key);
      if (current != null) {
        aggregator.accept(current);
        return;
      }
      V created = initializer.get();
      pending.put(key, created);
      nextTickScheduler.accept(() -> deliver(key, created));
    }

    void cancel(K key) {
      pending.remove(key);
    }

    private void deliver(K key, V expected) {
      if (pending.get(key) != expected) {
        return;
      }
      pending.remove(key);
      processor.accept(expected);
    }
  }
}
