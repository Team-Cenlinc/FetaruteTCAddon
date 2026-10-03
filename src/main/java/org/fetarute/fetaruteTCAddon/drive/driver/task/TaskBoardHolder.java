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
  private final List<TaskBoardEntries.Row> rows;
  private Inventory inventory;

  public TaskBoardHolder(
      UUID playerId,
      String operatorCode,
      String stationCode,
      String stationName,
      List<TaskBoardEntries.Row> rows) {
    this.playerId = playerId;
    this.operatorCode = operatorCode;
    this.stationCode = stationCode;
    this.stationName = stationName;
    this.rows = List.copyOf(rows);
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

  /** 这一格对应的车次；空格时为空。 */
  public Optional<TaskBoardEntries.Row> rowAt(int slot) {
    if (slot < 0 || slot >= rows.size() || slot >= TaskBoard.ENTRY_SLOTS) {
      return Optional.empty();
    }
    return Optional.of(rows.get(slot));
  }
}
