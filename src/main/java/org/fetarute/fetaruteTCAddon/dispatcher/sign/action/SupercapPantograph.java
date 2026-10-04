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
import org.fetarute.fetaruteTCAddon.FetaruteTCAddon;
import org.fetarute.fetaruteTCAddon.drive.session.DriveSessionManager;
import org.fetarute.fetaruteTCAddon.drive.setup.PowerSupply;
import org.fetarute.fetaruteTCAddon.drive.setup.TrainPower;

/**
 * 超级电容车的受电弓：升弓由站台上的 TC {@code animate} 牌子负责；停站结束放行发车时，受电弓还升着就排一个降弓（倒放）。
 *
 * <p>降弓排队、不 {@code reset}（见 {@link QueuedAnimations}）：牌子的升弓还没放完就等它放完再降，也不会清掉别的排队动画。
 * 没放升弓牌子的站（第三轨充电）弓本来就是降着的，什么也不做。
 */
final class SupercapPantograph {

  /** 认这些受电弓动画名（不区分大小写）。 */
  static final List<String> PANTOGRAPHS = List.of("ptg5", "ptg6");

  private SupercapPantograph() {}

  /**
   * 超级电容车停站结束：把还升着的受电弓降下。
   *
   * @return 排了降弓的附件数
   */
  static int lowerIfRaised(FetaruteTCAddon plugin, MinecartGroup group) {
    if (group == null || !group.isValid() || !isSupercap(plugin, group)) {
      return 0;
    }
    List<Attachment> attachments = new ArrayList<>();
    for (MinecartMember<?> member : group) {
      if (member != null
          && member.getAttachments() != null
          && member.getAttachments().isAttached()
          && member.getAttachments().getRootAttachment() != null) {
        attachments.addAll(
            HelperMethods.listAllAttachments(member.getAttachments().getRootAttachment()));
      }
    }
    int lowered = 0;
    for (String name : PANTOGRAPHS) {
      List<Attachment> raised = new ArrayList<>();
      for (Attachment attachment : attachments) {
        if (attachment.isAttached() && raised(attachment, name)) {
          raised.add(attachment);
        }
      }
      if (raised.isEmpty()) {
        continue;
      }
      AnimationOptions down = new AnimationOptions(name);
      down.setSpeed(-1.0);
      lowered += QueuedAnimations.withFallback(QueuedAnimations.playNamed(raised, down)).size();
    }
    return lowered;
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

  private static boolean sameName(Animation animation, String name) {
    String own = animation.getOptions().getName();
    return own != null && own.equalsIgnoreCase(name);
  }

  private static boolean isSupercap(FetaruteTCAddon plugin, MinecartGroup group) {
    DriveSessionManager drive = plugin == null ? null : plugin.getDriveSessionManager();
    PowerSupply fallback = drive == null ? PowerSupply.PTG5 : drive.config().defaultPower();
    return TrainPower.of(group.getProperties(), fallback).storesEnergy();
  }
}
