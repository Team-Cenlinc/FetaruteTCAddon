package org.fetarute.fetaruteTCAddon.drive.menu;

/** 停车后菜单里只显示、不操作的表计与指示（simulation 级）。 */
public enum MenuIndicator {
  /** 主风缸压力表。 */
  MAIN_RESERVOIR,
  /** 制动缸压力表。 */
  BRAKE_CYLINDER,
  /** 制动管压力表（仅机车牵引）。 */
  BRAKE_PIPE,
  /** 故障指示：列出当前故障与处置方法。 */
  FAULTS
}
