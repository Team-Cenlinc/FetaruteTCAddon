package org.fetarute.fetaruteTCAddon.drive.driver;

/** 驾驶调度列车的方式。 */
public enum DrivingMode {
  /** 人工驾驶：驾驶员操纵牵引、制动、停车与车门。 */
  MANUAL,
  /** ATO 辅助：自动运行代为操纵与停车，驾驶员负责确认关门与发车。 */
  ATO
}
