package org.fetarute.fetaruteTCAddon.drive.driver.task;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/** 任务板箱子界面的持有者：记住是哪名玩家、哪个车站、每格对应的车次。 */
public final class TaskBoardHolder implements InventoryHolder {

  private final UUID playerId;
  private final String operatorCode;
  private final String stationCode;
  private final String stationName;
  private final List<TaskBoardEntries.Entry> entries;
  private Inventory inventory;

  public TaskBoardHolder(
      UUID playerId,
      String operatorCode,
      String stationCode,
      String stationName,
      List<TaskBoardEntries.Entry> entries) {
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

  /** 这一格可领取的车次；空格或已被领走时为空。 */
  public Optional<TaskBoardEntries.Row> rowAt(int slot) {
    return entryAt(slot).filter(entry -> !entry.claimed()).map(TaskBoardEntries.Entry::row);
  }
}
