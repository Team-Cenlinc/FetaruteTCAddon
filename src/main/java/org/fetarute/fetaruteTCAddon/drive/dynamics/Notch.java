package org.fetarute.fetaruteTCAddon.drive.dynamics;

import java.util.Optional;

/**
 * 司机控制器的档位，与快捷栏九个槽位一一对应：
 *
 * <pre>
 * 槽位  1   2   3   4  5   6   7   8   9
 * 档位  P3  P2  P1  N  B1  B2  B3  B4  EB
 * </pre>
 *
 * <p>{@code P} 为牵引，数字越大牵引力越大；{@code N} 为惰行；{@code B} 为常用制动，数字越大制动力越大；{@code EB} 为紧急制动。
 */
public enum Notch {
  P3(0, Kind.TRACTION, 3),
  P2(1, Kind.TRACTION, 2),
  P1(2, Kind.TRACTION, 1),
  N(3, Kind.COAST, 0),
  B1(4, Kind.BRAKE, 1),
  B2(5, Kind.BRAKE, 2),
  B3(6, Kind.BRAKE, 3),
  B4(7, Kind.BRAKE, 4),
  EB(8, Kind.EMERGENCY, 0);

  /** 档位类别。 */
  public enum Kind {
    TRACTION,
    COAST,
    BRAKE,
    EMERGENCY
  }

  /** 快捷栏槽位数量，也是档位数量。 */
  public static final int SLOT_COUNT = 9;

  private final int slot;
  private final Kind kind;
  private final int step;

  Notch(int slot, Kind kind, int step) {
    this.slot = slot;
    this.kind = kind;
    this.step = step;
  }

  /** 对应的快捷栏槽位，从 0 起。 */
  public int slot() {
    return slot;
  }

  public Kind kind() {
    return kind;
  }

  /** 同一类别内的级数：牵引 1–3、制动 1–4；惰行与紧急制动为 0。 */
  public int step() {
    return step;
  }

  public boolean isTraction() {
    return kind == Kind.TRACTION;
  }

  public boolean isBrake() {
    return kind == Kind.BRAKE || kind == Kind.EMERGENCY;
  }

  /** 按快捷栏槽位取档位；槽位不在 0–8 内时为空。 */
  public static Optional<Notch> fromSlot(int slot) {
    for (Notch notch : values()) {
      if (notch.slot == slot) {
        return Optional.of(notch);
      }
    }
    return Optional.empty();
  }
}
