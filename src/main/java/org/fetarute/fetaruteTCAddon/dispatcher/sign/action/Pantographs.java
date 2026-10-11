package org.fetarute.fetaruteTCAddon.dispatcher.sign.action;

import com.bergerkiller.bukkit.tc.attachments.animation.Animation;
import com.bergerkiller.bukkit.tc.attachments.animation.AnimationOptions;
import com.bergerkiller.bukkit.tc.attachments.api.Attachment;
import com.bergerkiller.bukkit.tc.attachments.api.AttachmentInternalState;
import com.bergerkiller.bukkit.tc.attachments.helper.HelperMethods;
import com.bergerkiller.bukkit.tc.controller.MinecartGroup;
import com.bergerkiller.bukkit.tc.controller.MinecartMember;
import java.util.ArrayList;
import java.util.List;

/**
 * 受电弓动画（{@code ptg5}/{@code ptg6}）的升降与状态：正放为升、倒放为降，一律排队（见 {@link QueuedAnimations}）。
 *
 * <p>升没升看附件上这个动画最后会停在哪：排队列表里最后一个同名动画，没有时看当前动画——TrainCarts 放完动画仍保留它作为当前动画， 附件重新挂载后才清空（这时模型回到默认姿态）。
 */
public final class Pantographs {

  /** 认这些受电弓动画名（不区分大小写）。 */
  public static final List<String> NAMES = List.of("ptg5", "ptg6");

  private Pantographs() {}

  /** 列车上全部已挂载的附件。 */
  static List<Attachment> attachments(MinecartGroup group) {
    List<Attachment> out = new ArrayList<>();
    if (group == null || !group.isValid()) {
      return out;
    }
    for (MinecartMember<?> member : group) {
      if (member != null
          && member.getAttachments() != null
          && member.getAttachments().isAttached()
          && member.getAttachments().getRootAttachment() != null) {
        out.addAll(HelperMethods.listAllAttachments(member.getAttachments().getRootAttachment()));
      }
    }
    return out;
  }

  /**
   * 这个附件上的受电弓动画最后会停在升起的状态：排队列表里最后一个同名动画是正放；队里没有时看当前动画是不是正放（正在升或已升到顶）。
   *
   * @param name 受电弓动画名
   */
  static boolean raised(Attachment attachment, String name) {
    AttachmentInternalState state = attachment.getInternalState();
    List<Animation> queue = state.nextAnimationQueue;
    for (int i = queue.size() - 1; i >= 0; i--) {
      Animation queued = queue.get(i);
      if (sameName(queued, name)) {
        return !queued.getOptions().isReversed();
      }
    }
    Animation current = state.currentAnimation;
    return current != null && sameName(current, name) && !current.getOptions().isReversed();
  }

  /**
   * 把列车上的这组受电弓升到或降到指定状态：已经是这个状态的不动，所以重复调用不会重放动画。
   *
   * @param name 受电弓动画名
   * @param raise {@code true} 升弓
   * @return 排了动画的附件数；附件没挂载（附近没有玩家）或模型没有这个动画时为 0
   */
  public static int set(MinecartGroup group, String name, boolean raise) {
    List<Attachment> attachments = attachments(group);
    return attachments.isEmpty() ? 0 : set(group, attachments, name, raise);
  }

  /**
   * 同 {@link #set(MinecartGroup, String, boolean)}，附件已取好。有的附件已升着时只给其余带这个动画的附件补升（不碰 {@code group}）：
   * 附件是逐节车厢挂载的，重新挂载过的车厢回到默认（降弓）姿态。
   */
  static int set(MinecartGroup group, List<Attachment> attachments, String name, boolean raise) {
    List<Attachment> raisedOnes = new ArrayList<>();
    List<Attachment> loweredOnes = new ArrayList<>();
    for (Attachment attachment : attachments) {
      if (attachment.isAttached()) {
        (raised(attachment, name) ? raisedOnes : loweredOnes).add(attachment);
      }
    }
    if (!raise) {
      return raisedOnes.isEmpty() ? 0 : play(raisedOnes, name, false);
    }
    if (!raisedOnes.isEmpty()) {
      if (loweredOnes.isEmpty()) {
        return 0;
      }
      AnimationOptions up = new AnimationOptions(name);
      up.setSpeed(1.0);
      return QueuedAnimations.withFallback(QueuedAnimations.playStored(loweredOnes, up)).size();
    }
    String actual = actualName(group, name);
    if (actual == null) {
      return 0;
    }
    AnimationOptions up = new AnimationOptions(actual);
    up.setSpeed(1.0);
    return QueuedAnimations.withFallback(QueuedAnimations.playNamed(group, up)).size();
  }

  /** 降下这些附件上的受电弓（倒放）。 */
  static int play(List<Attachment> targets, String name, boolean raise) {
    AnimationOptions options = new AnimationOptions(name);
    options.setSpeed(raise ? 1.0 : -1.0);
    return QueuedAnimations.withFallback(QueuedAnimations.playNamed(targets, options)).size();
  }

  /** 模型里这个动画名的实际写法（不区分大小写）；没有时为 null。 */
  private static String actualName(MinecartGroup group, String wanted) {
    if (group.getAnimationNames() == null) {
      return null;
    }
    for (String name : group.getAnimationNames()) {
      if (name != null && name.equalsIgnoreCase(wanted)) {
        return name;
      }
    }
    return null;
  }

  private static boolean sameName(Animation animation, String name) {
    String own = animation.getOptions().getName();
    return own != null && own.equalsIgnoreCase(name);
  }
}
