package org.fetarute.fetaruteTCAddon.drive.driver.task;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.fetarute.fetaruteTCAddon.drive.SimulationLevel;

/** 任务板箱子界面的持有者：记住是哪名玩家、哪个车站、每格对应的车次，以及玩家此刻选定的仿真等级。 */
public final class TaskBoardHolder implements InventoryHolder {

  private final UUID playerId;
  private final String operatorCode;
  private final String stationCode;
  private final String stationName;
  private final List<TaskBoardEntries.Entry> entries;
  private final TaskBoard.Kind kind;
  private Inventory inventory;
  private SimulationLevel level;

  public TaskBoardHolder(
      UUID playerId,
      String operatorCode,
      String stationCode,
      String stationName,
      List<TaskBoardEntries.Entry> entries) {
    this(playerId, operatorCode, stationCode, stationName, entries, TaskBoard.Kind.DRIVER);
  }

  /**
   * @param kind 给驾驶员还是车掌用
   */
  public TaskBoardHolder(
      UUID playerId,
      String operatorCode,
      String stationCode,
      String stationName,
      List<TaskBoardEntries.Entry> entries,
      TaskBoard.Kind kind) {
    this.kind = kind == null ? TaskBoard.Kind.DRIVER : kind;
    this.playerId = playerId;
    this.operatorCode = operatorCode;
    this.stationCode = stationCode;
    this.stationName = stationName;
    this.entries = List.copyOf(entries);
  }

  void bind(Inventory inventory) {
    this.inventory = inventory;
  }

  @Override
  public Inventory getInventory() {
    return inventory;
  }

  public UUID playerId() {
    return playerId;
  }

  /** 给驾驶员还是车掌用。 */
  public TaskBoard.Kind kind() {
    return kind;
  }

  public String operatorCode() {
    return operatorCode;
  }

  public String stationCode() {
    return stationCode;
  }

  public String stationName() {
    return stationName;
  }

  /** 这一格对应的条目（含已被领走的）；空格时为空。 */
  public Optional<TaskBoardEntries.Entry> entryAt(int slot) {
    if (slot < 0 || slot >= entries.size() || slot >= TaskBoard.ENTRY_SLOTS) {
      return Optional.empty();
    }
    return Optional.of(entries.get(slot));
  }

  /** 玩家此刻选定的仿真等级；玩家不能自选等级（界面上没有难度按钮）时为空。 */
  public Optional<SimulationLevel> level() {
    return Optional.ofNullable(level);
  }

  void setLevel(SimulationLevel level) {
    this.level = level;
  }

  /** 这一格是不是难度按钮、对应哪个等级；界面上没有难度按钮时为空。 */
  public Optional<SimulationLevel> levelAt(int slot) {
    return level == null ? Optional.empty() : TaskBoard.levelOfSlot(slot);
  }

  /** 这一格可领取的车次；空格或已被领走时为空。 */
  public Optional<TaskBoardEntries.Row> rowAt(int slot) {
    return entryAt(slot).filter(entry -> !entry.claimed()).map(TaskBoardEntries.Entry::row);
  }
}
