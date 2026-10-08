package org.fetarute.fetaruteTCAddon.drive.tutorial;

import java.time.Duration;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.title.Title;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.fetarute.fetaruteTCAddon.drive.DrivePermissions;
import org.fetarute.fetaruteTCAddon.drive.session.DriveSession;
import org.fetarute.fetaruteTCAddon.drive.sound.DriveCue;
import org.fetarute.fetaruteTCAddon.drive.sound.DriveSounds;
import org.fetarute.fetaruteTCAddon.utils.LocaleManager;

/**
 * 新手教程与情境提示的服务器侧：记住谁在做教程、把引擎的事件呈现给玩家，并把完成标记与情境提示标记存进玩家的持久数据。只在服务器主线程调用。
 *
 * <p>动作栏与 Boss 栏被驾驶 HUD 占用，教程只用聊天（带进度与可点击的按钮）、短副标题与提示音。
 *
 * <p>驾驶会话管理器只在会话开始、逐 tick 维护、会话结束与车门不可用时喂数据，判定都在 {@link TutorialEngine} 与 {@link DriveTip}。
 * 这几个入口自己兜住异常：教程出错只记日志，不影响驾驶会话。
 */
public final class DriveTutorials {

  /** 这一次教程不能再算完整做完的原因。 */
  public enum Forfeit {
    /** 跳过了练习步骤（说明类步骤点「继续」不算）。 */
    SKIPPED_PRACTICE,
    /** 退出了教程。 */
    EXITED
  }

  /** 每隔多少 tick 推进一次教程与情境提示。 */
  public static final int TICK_INTERVAL = 5;

  private static final Title.Times SUBTITLE_TIMES =
      Title.Times.times(Duration.ofMillis(250), Duration.ofMillis(3500), Duration.ofMillis(500));

  private final Supplier<LocaleManager> locale;
  private final DriveSounds sounds;
  private final Logger logger;
  private final NamespacedKey doneKey;
  private final Map<DriveTip, NamespacedKey> tipKeys = new EnumMap<>(DriveTip.class);
  private final Map<UUID, TutorialEngine> running = new HashMap<>();

  /** 不在驾驶时要求开始教程的玩家：下一次开始驾驶时开始。 */
  private final Set<UUID> armed = new HashSet<>();

  /** 下一次推进时开始教程（排在“已开始驾驶”的提示之后）。 */
  private final Set<UUID> startPending = new HashSet<>();

  /** 下一次推进时提示要不要做教程。 */
  private final Set<UUID> offerPending = new HashSet<>();

  /** 本次服务器运行中已提示过要不要做教程的玩家：没有选择也不在每次开车时反复提示。 */
  private final Set<UUID> offered = new HashSet<>();

  /** 驾驶中的玩家已经出过的情境提示；没有教程权限的玩家不在其中。 */
  private final Map<UUID, Set<DriveTip>> tipsShown = new HashMap<>();

  /** 本次教程里跳过了练习步骤（说明类步骤点「继续」不算）的玩家。 */
  private final Set<UUID> skippedPractice = new HashSet<>();

  /** 教程做完时的通知：玩家与是否完整做完（没有跳过练习步骤）。驾驶证的教程考试据此判定。 */
  private BiConsumer<Player, Boolean> finishListener = (player, complete) -> {};

  /** 这一次教程不能再算完整做完时的通知（每次教程至多一次）：教程考试据此马上告诉考生可以从头重做。 */
  private BiConsumer<Player, Forfeit> forfeitListener = (player, reason) -> {};

  /**
   * @param sounds 驾驶提示音（教程的音效也按 drive.yml 的 sounds 段配置与开关）
   */
  public DriveTutorials(Plugin plugin, Supplier<LocaleManager> locale, DriveSounds sounds) {
    this.locale = locale;
    this.sounds = sounds;
    this.logger = plugin.getLogger();
    this.doneKey = new NamespacedKey(plugin, "drive_tutorial_done");
    for (DriveTip tip : DriveTip.values()) {
      tipKeys.put(
          tip,
          new NamespacedKey(
              plugin, "drive_tip_" + tip.storageKey().replace('-', '_').toLowerCase(Locale.ROOT)));
    }
  }

  /** 设置教程做完时的通知；传入 {@code null} 恢复空通知。 */
  public void onFinished(BiConsumer<Player, Boolean> listener) {
    this.finishListener = listener == null ? (player, complete) -> {} : listener;
  }

  /** 设置这一次教程不能再算完整做完时的通知；传入 {@code null} 恢复空通知。 */
  public void onForfeit(BiConsumer<Player, Forfeit> listener) {
    this.forfeitListener = listener == null ? (player, reason) -> {} : listener;
  }

  // ---- 驾驶会话管理器喂数据 ----

  /** 驾驶会话开始：之前要求过开始教程就开始；否则第一次驾驶（没有完成标记）时提示要不要做教程。 */
  public void onSessionStarted(Player player) {
    guard(
        "开始",
        () -> {
          UUID id = player.getUniqueId();
          if (!player.hasPermission(DrivePermissions.TUTORIAL)) {
            armed.remove(id);
            tipsShown.remove(id);
            return;
          }
          tipsShown.put(id, shownTips(player));
          if (armed.remove(id)) {
            startPending.add(id);
            return;
          }
          if (!completed(player) && offered.add(id)) {
            offerPending.add(id);
          }
        });
  }

  /**
   * 推进教程并检查情境提示。由驾驶会话管理器每 {@link #TICK_INTERVAL} tick 调用一次。
   *
   * @param nowTick 当前服务器 tick
   */
  public void tick(Player player, DriveSession session, long nowTick) {
    guard("推进", () -> advance(player, session, nowTick));
  }

  private void advance(Player player, DriveSession session, long nowTick) {
    UUID id = player.getUniqueId();
    if (offerPending.remove(id)) {
      player.sendMessage(locale.get().component("drive.tutorial.offer"));
    }
    if (startPending.remove(id)) {
      begin(player);
    }
    TutorialEngine engine = running.get(id);
    Set<DriveTip> shown = tipsShown.get(id);
    boolean tipsLeft = shown != null && shown.size() < DriveTip.values().length;
    if (engine == null && !tipsLeft) {
      return;
    }
    TutorialSnapshot snapshot = TutorialSnapshots.of(session, nowTick);
    if (engine != null) {
      render(player, engine.tick(snapshot, nowTick));
    }
    if (tipsLeft) {
      DriveTip.firstDue(snapshot, shown).ifPresent(tip -> showTip(player, tip, shown));
    }
  }

  /** 这辆车没有车门动画：正在练开关车门就略过。 */
  public void onDoorUnavailable(Player player) {
    guard(
        "略过车门练习",
        () -> {
          TutorialEngine engine = running.get(player.getUniqueId());
          if (engine != null) {
            render(player, engine.doorsUnavailable());
          }
        });
  }

  /**
   * 驾驶会话结束：在最后一步则教程完成，否则中断。
   *
   * @param player 玩家；已下线时为 {@code null}（中断，不提示）
   * @param reason 结束原因；插件停用时不提示
   */
  public void onSessionEnded(UUID playerId, Player player, DriveSession.EndReason reason) {
    startPending.remove(playerId);
    offerPending.remove(playerId);
    tipsShown.remove(playerId);
    TutorialEngine engine = running.remove(playerId);
    if (engine == null
        || player == null
        || !player.isOnline()
        || reason == DriveSession.EndReason.DISABLED) {
      return;
    }
    guard("结束", () -> render(player, engine.sessionEnded()));
  }

  /** 教程出错只记日志：驾驶会话管理器的逐 tick 维护遇到异常会结束会话，不能让教程拖累驾驶。 */
  private void guard(String what, Runnable body) {
    try {
      body.run();
    } catch (RuntimeException ex) {
      logger.warning("新手教程" + what + "失败: " + ex);
    }
  }

  // ---- 命令 ----

  /**
   * 开始教程：驾驶中立即开始，否则下一次开始驾驶时开始。
   *
   * @param session 玩家当前的驾驶会话；不在驾驶时为 {@code null}
   * @return 给玩家的提示语言键；已直接给出第一步时为 {@code null}
   */
  public String start(Player player, DriveSession session, long nowTick) {
    UUID id = player.getUniqueId();
    TutorialEngine engine = running.get(id);
    if (engine != null) {
      return "drive.tutorial.command.already-running";
    }
    if (session == null) {
      armed.add(id);
      return "drive.tutorial.command.armed";
    }
    offerPending.remove(id);
    startPending.remove(id);
    tipsShown.computeIfAbsent(id, ignored -> shownTips(player));
    begin(player);
    render(player, running.get(id).tick(TutorialSnapshots.of(session, nowTick), nowTick));
    return null;
  }

  /**
   * 从第一步重新开始：放弃正在进行的这一次（不算做完），驾驶中立即开始，否则下一次开始驾驶时开始。
   *
   * @param session 玩家当前的驾驶会话；不在驾驶时为 {@code null}
   * @return 给玩家的提示语言键；已直接给出第一步时为 {@code null}
   */
  public String restart(Player player, DriveSession session, long nowTick) {
    UUID id = player.getUniqueId();
    running.remove(id);
    skippedPractice.remove(id);
    return start(player, session, nowTick);
  }

  /**
   * 退出教程（或不做教程）：记为已完成，此后不再自动提示，可随时重新开始。
   *
   * @return 给玩家的提示语言键
   */
  public String stop(Player player) {
    UUID id = player.getUniqueId();
    boolean wasRunning = running.remove(id) != null;
    // 报了教程考试、还没上车时教程只是“下次开车时开始”：取消它同样要告诉考试一方，否则考试悄悄挂到超时。
    boolean wasPending = armed.remove(id) | startPending.remove(id);
    offerPending.remove(id);
    markCompleted(player);
    if ((wasRunning || wasPending) && !skippedPractice.remove(id)) {
      guard("作废通知", () -> forfeitListener.accept(player, Forfeit.EXITED));
    }
    return wasRunning ? "drive.tutorial.command.stopped" : "drive.tutorial.command.dismissed";
  }

  /**
   * 重置：清掉完成标记与全部情境提示标记，下次开始驾驶时重新提示；正在进行的教程一并结束。
   *
   * @return 给玩家的提示语言键
   */
  public String reset(Player player) {
    UUID id = player.getUniqueId();
    running.remove(id);
    armed.remove(id);
    startPending.remove(id);
    offerPending.remove(id);
    offered.remove(id);
    PersistentDataContainer data = player.getPersistentDataContainer();
    data.remove(doneKey);
    for (NamespacedKey key : tipKeys.values()) {
      data.remove(key);
    }
    tipsShown.computeIfPresent(id, (ignored, shown) -> EnumSet.noneOf(DriveTip.class));
    return "drive.tutorial.command.reset";
  }

  /**
   * 跳过当前这一步。
   *
   * @param session 玩家当前的驾驶会话；不在驾驶时为 {@code null}
   * @return 给玩家的提示语言键；已推进时为 {@code null}
   */
  public String skip(Player player, DriveSession session, long nowTick) {
    TutorialEngine engine = running.get(player.getUniqueId());
    if (engine == null || session == null) {
      return "drive.tutorial.command.not-running";
    }
    render(player, engine.skip(TutorialSnapshots.of(session, nowTick), nowTick));
    return null;
  }

  // ---- 呈现 ----

  private void begin(Player player) {
    running.put(player.getUniqueId(), new TutorialEngine());
    skippedPractice.remove(player.getUniqueId());
    player.sendMessage(locale.get().component("drive.tutorial.started"));
  }

  private void render(Player player, List<TutorialEvent> events) {
    LocaleManager messages = locale.get();
    for (TutorialEvent event : events) {
      if (event instanceof TutorialEvent.StepStarted started) {
        announce(player, messages, started);
      } else if (event instanceof TutorialEvent.StepCompleted completed) {
        if (!completed.skipped()) {
          sounds.play(player, DriveCue.TUTORIAL_STEP);
        } else if (!completed.step().info() && skippedPractice.add(player.getUniqueId())) {
          guard("作废通知", () -> forfeitListener.accept(player, Forfeit.SKIPPED_PRACTICE));
        }
      } else if (event instanceof TutorialEvent.Reminder reminder) {
        subtitle(
            player, messages.component("drive.tutorial.steps." + reminder.key() + ".subtitle"));
      } else if (event instanceof TutorialEvent.DoorsUnavailable) {
        player.sendMessage(messages.component("drive.tutorial.doors-unavailable"));
      } else if (event instanceof TutorialEvent.Finished) {
        running.remove(player.getUniqueId());
        markCompleted(player);
        player.sendMessage(messages.component("drive.tutorial.finished"));
        subtitle(player, messages.component("drive.tutorial.finished-subtitle"));
        sounds.play(player, DriveCue.TUTORIAL_FINISH);
        boolean complete = !skippedPractice.remove(player.getUniqueId());
        guard("完成通知", () -> finishListener.accept(player, complete));
      } else if (event instanceof TutorialEvent.Interrupted) {
        running.remove(player.getUniqueId());
        skippedPractice.remove(player.getUniqueId());
        player.sendMessage(messages.component("drive.tutorial.interrupted"));
      }
    }
  }

  /** 一步的说明：聊天里带进度与按钮，副标题给一句短提示。 */
  private void announce(Player player, LocaleManager messages, TutorialEvent.StepStarted started) {
    String base = "drive.tutorial.steps." + started.key();
    String buttons =
        started.step() == TutorialStep.FINISH
            ? "drive.tutorial.buttons.finish"
            : started.step().info()
                ? "drive.tutorial.buttons.continue"
                : "drive.tutorial.buttons.skip";
    player.sendMessage(
        messages.component(
            "drive.tutorial.step",
            TagResolver.resolver(
                Placeholder.unparsed("index", String.valueOf(started.index())),
                Placeholder.unparsed("total", String.valueOf(started.total())),
                Placeholder.component("text", messages.component(base + ".chat")),
                Placeholder.component("buttons", messages.component(buttons)))));
    subtitle(player, messages.component(base + ".subtitle"));
  }

  private void showTip(Player player, DriveTip tip, Set<DriveTip> shown) {
    shown.add(tip);
    player.getPersistentDataContainer().set(tipKeys.get(tip), PersistentDataType.BYTE, (byte) 1);
    LocaleManager messages = locale.get();
    String base = "drive.tutorial.tips." + tip.key();
    player.sendMessage(
        messages.component(
            "drive.tutorial.tip",
            TagResolver.resolver(
                Placeholder.component("text", messages.component(base + ".chat")))));
    subtitle(player, messages.component(base + ".subtitle"));
    sounds.play(player, DriveCue.TUTORIAL_TIP);
  }

  private static void subtitle(Player player, Component text) {
    player.showTitle(Title.title(Component.empty(), text, SUBTITLE_TIMES));
  }

  // ---- 持久数据 ----

  private boolean completed(Player player) {
    return player.getPersistentDataContainer().has(doneKey, PersistentDataType.BYTE);
  }

  private void markCompleted(Player player) {
    player.getPersistentDataContainer().set(doneKey, PersistentDataType.BYTE, (byte) 1);
  }

  private Set<DriveTip> shownTips(Player player) {
    PersistentDataContainer data = player.getPersistentDataContainer();
    Set<DriveTip> shown = EnumSet.noneOf(DriveTip.class);
    for (Map.Entry<DriveTip, NamespacedKey> entry : tipKeys.entrySet()) {
      if (data.has(entry.getValue(), PersistentDataType.BYTE)) {
        shown.add(entry.getKey());
      }
    }
    return shown;
  }
}
