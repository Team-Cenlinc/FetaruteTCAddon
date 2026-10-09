package org.fetarute.fetaruteTCAddon.drive.hud;

import io.papermc.paper.scoreboard.numbers.NumberFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.fetarute.fetaruteTCAddon.drive.session.DriveSession;
import org.fetarute.fetaruteTCAddon.utils.LocaleManager;

/**
 * 驾驶员的侧边栏：每位驾驶员一块独立计分板，开始驾驶时换上，结束时换回原来的计分板。
 *
 * <p>每行用分数的自定义显示名表示，并隐藏右侧的分数数字；只重发变了的行。开始驾驶时换上一次，被别的插件换走就让给它。 乘客的计分板 HUD 在驾驶期间让位（见乘客 HUD 管理器），
 * 换上时若原计分板是乘客 HUD 的，结束后换回主计分板，交还乘客 HUD 重新接管。只能在服务器主线程调用。
 */
public final class DriveSidebar {

  private static final String OBJECTIVE = "fta_drive";

  /** 乘客计分板 HUD 的目标名；原计分板是它时不把它当作要还原的计分板。 */
  private static final String PASSENGER_OBJECTIVE = "fta_hud";

  private final LocaleManager locale;
  private final Map<UUID, State> states = new HashMap<>();

  public DriveSidebar(LocaleManager locale) {
    this.locale = locale;
  }

  /**
   * 刷新玩家的侧边栏；还没有时先换上。
   *
   * <p>只在第一次换上：之后若被别的插件换成它的计分板，就让给它，不再刷新也不抢回来，免得两边每秒互相覆盖。
   *
   * @return 玩家此刻看到的是驾驶员侧边栏（被别的插件换掉时为 {@code false}）
   */
  public boolean update(Player player, DriveSession session) {
    return update(
        player,
        "drive.sidebar.title",
        session.trainName(),
        DriveSidebarRows.build(session, Bukkit.getCurrentTick()));
  }

  /**
   * 按给定的标题与各行刷新（车掌也用这块侧边栏）。
   *
   * @param titleKey 标题的语言键，占位符 {@code <train>}
   * @param train 列车名
   * @param rows 各行
   * @return 玩家此刻看到的是这块侧边栏
   */
  public boolean update(
      Player player, String titleKey, String train, List<DriveSidebarRows.Row> rows) {
    State state = states.get(player.getUniqueId());
    if (state == null) {
      state = create(player);
      states.put(player.getUniqueId(), state);
      player.setScoreboard(state.scoreboard);
    } else if (player.getScoreboard() != state.scoreboard) {
      return false;
    }
    // 每次刷新都解析 MiniMessage 很贵：行的内容（语言键与占位符）没变、语言文件也没重载时，沿用上次渲染的结果。
    long generation = locale.generation();
    boolean reloaded = generation != state.localeGeneration;
    state.localeGeneration = generation;
    if (reloaded
        || state.title == null
        || !train.equals(state.titleTrain)
        || !titleKey.equals(state.titleKey)) {
      Component title = locale.component(titleKey, Map.of("train", train));
      if (!title.equals(state.title)) {
        state.objective.displayName(title);
        state.title = title;
      }
      state.titleTrain = train;
      state.titleKey = titleKey;
    }
    List<Component> lines = new ArrayList<>(rows.size());
    for (int i = 0; i < rows.size(); i++) {
      DriveSidebarRows.Row row = rows.get(i);
      boolean unchanged =
          !reloaded
              && i < state.rows.size()
              && i < state.lines.size()
              && row.equals(state.rows.get(i));
      lines.add(unchanged ? state.lines.get(i) : render(row));
    }
    state.rows = rows;
    for (int i = 0; i < lines.size(); i++) {
      Component line = lines.get(i);
      if (i < state.lines.size() && line.equals(state.lines.get(i))) {
        continue;
      }
      var score = state.objective.getScore(entry(i));
      score.setScore(lines.size() - i);
      score.customName(line);
    }
    for (int i = lines.size(); i < state.lines.size(); i++) {
      state.scoreboard.resetScores(entry(i));
    }
    if (lines.size() != state.lines.size()) {
      // 行数变了，各行的排序分数都要跟着变。
      for (int i = 0; i < lines.size(); i++) {
        state.objective.getScore(entry(i)).setScore(lines.size() - i);
      }
    }
    state.lines = lines;
    return true;
  }

  /** 撤下侧边栏，换回原来的计分板。 */
  public void hide(Player player) {
    State state = states.remove(player.getUniqueId());
    if (state != null && player.getScoreboard() == state.scoreboard) {
      player.setScoreboard(state.previous);
    }
  }

  /** 玩家已不在线：只丢掉记录，下次上线时服务器会给他新的计分板。 */
  public void forget(UUID playerId) {
    states.remove(playerId);
  }

  /** 撤下所有侧边栏。插件停用时调用。 */
  public void hideAll() {
    for (UUID id : new ArrayList<>(states.keySet())) {
      Player player = Bukkit.getPlayer(id);
      if (player != null) {
        hide(player);
      } else {
        states.remove(id);
      }
    }
  }

  private State create(Player player) {
    Scoreboard previous = player.getScoreboard();
    if (previous.getObjective(PASSENGER_OBJECTIVE) != null) {
      previous = Bukkit.getScoreboardManager().getMainScoreboard();
    }
    Scoreboard scoreboard = Bukkit.getScoreboardManager().getNewScoreboard();
    Objective objective =
        scoreboard.registerNewObjective(OBJECTIVE, Criteria.DUMMY, Component.empty());
    objective.setDisplaySlot(DisplaySlot.SIDEBAR);
    objective.numberFormat(NumberFormat.blank());
    return new State(scoreboard, objective, previous);
  }

  private Component render(DriveSidebarRows.Row row) {
    Component label = locale.component(row.labelKey());
    Component value = locale.component(row.valueKey(), row.values());
    return locale.component(
        "drive.sidebar.row",
        TagResolver.builder()
            .resolver(Placeholder.component("label", label))
            .resolver(Placeholder.component("value", value))
            .build());
  }

  /** 每行的分数条目名：只作内部标识，显示的是自定义名。 */
  private static String entry(int index) {
    return "fta_drive_" + index;
  }

  private static final class State {
    private final Scoreboard scoreboard;
    private final Objective objective;
    private final Scoreboard previous;
    private Component title;
    private String titleTrain;
    private String titleKey;
    private long localeGeneration = -1L;
    private List<DriveSidebarRows.Row> rows = List.of();
    private List<Component> lines = List.of();

    private State(Scoreboard scoreboard, Objective objective, Scoreboard previous) {
      this.scoreboard = scoreboard;
      this.objective = objective;
      this.previous = previous;
    }
  }
}
