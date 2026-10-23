package org.fetarute.fetaruteTCAddon.coupler;

import com.bergerkiller.bukkit.tc.controller.MinecartGroup;
import com.bergerkiller.bukkit.tc.controller.MinecartMember;

/**
 * 车钩开闭：记在车厢自己身上（车厢级标签），前后按车厢自身朝向区分，列车调头不影响。不写 = 锁上。
 *
 * <p>只有两端车钩都开着才联挂；车钩只管受管列车与用过扳手的车，其余玩家的车保持 TrainCarts 原样（碰上即挂）。
 */
public final class CouplerState {

  /** 车厢前端（按车厢自身朝向）车钩开着。 */
  static final String TAG_FRONT = "FTA_COUPLER_FRONT";

  /** 车厢后端车钩开着。 */
  static final String TAG_REAR = "FTA_COUPLER_REAR";

  private static final String OPEN = "open";

  private CouplerState() {}

  /** 车厢的一端（按车厢自身朝向）。 */
  public enum End {
    FRONT,
    REAR;

    /** 另一端。 */
    public End opposite() {
      return this == FRONT ? REAR : FRONT;
    }

    String tag() {
      return this == FRONT ? TAG_FRONT : TAG_REAR;
    }
  }

  /** 这一端车钩是否开着。 */
  public static boolean isOpen(MinecartMember<?> member, End end) {
    return CarTags.read(member, end.tag()).filter(OPEN::equalsIgnoreCase).isPresent();
  }

  /** 打开或锁上这一端车钩。 */
  public static void setOpen(MinecartMember<?> member, End end, boolean open) {
    if (open) {
      CarTags.write(member, end.tag(), OPEN);
    } else {
      CarTags.remove(member, end.tag());
    }
  }

  /** 这列车有没有用过扳手（有车厢记着车钩或来源编组）。 */
  public static boolean touched(MinecartGroup group) {
    if (group == null) {
      return false;
    }
    for (MinecartMember<?> member : group) {
      for (String key : CarTags.CAR_KEYS) {
        if (CarTags.read(member, key).isPresent()) {
          return true;
        }
      }
    }
    return false;
  }
}
