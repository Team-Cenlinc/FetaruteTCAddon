package org.fetarute.fetaruteTCAddon.dispatcher.sign.action;

import com.bergerkiller.bukkit.tc.TCConfig;
import com.bergerkiller.bukkit.tc.attachments.animation.Animation;
import com.bergerkiller.bukkit.tc.attachments.animation.AnimationOptions;
import com.bergerkiller.bukkit.tc.attachments.api.Attachment;
import com.bergerkiller.bukkit.tc.attachments.helper.HelperMethods;
import com.bergerkiller.bukkit.tc.controller.MinecartGroup;
import com.bergerkiller.bukkit.tc.controller.MinecartMember;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * 本插件播放的模型动画（车门、受电弓）一律排队，不带 {@code reset}。
 *
 * <p>TrainCarts 每个附件只有一个当前动画和一个排队列表（{@code Attachment#startAnimation}）：带 {@code reset}、或不带 {@code
 * queue} 的动画都会顶掉当前动画并<b>清空排队列表</b>，只有带 {@code queue} 的才排到队尾，等当前动画放完由 TrainCarts
 * 取出并从头（反向时从末尾）开始播。所以排队既保证从头播，又不会清掉别处（TC 牌子 {@code animate queue}、命令 {@code --queue}）排进去的动画。
 *
 * <p>附件上卡着放不完的动画（循环、速度为 0）时排队永远轮不到：排队超过 {@link #FORCE_AFTER_TICKS} 还没轮到就带 {@code reset} 强行播放（见
 * {@link Ticket#forceStuck()}）。列车附近没有玩家时附件已卸下，按名字播放什么也不做，不会积压。
 */
public final class QueuedAnimations {

  /** 排队超过这么久（tick）还没轮到就强行播放。 */
  public static final long FORCE_AFTER_TICKS = 60L;

  private QueuedAnimations() {}

  /** 一次排队：各附件上排进去的那个动画实例，兜底时按实例认出来。 */
  public static final class Ticket {

    private record Entry(Attachment attachment, Animation animation) {}

    private static final Ticket EMPTY = new Ticket(List.of());

    private final List<Entry> entries;

    private Ticket(List<Entry> entries) {
      this.entries = entries;
    }

    /** 什么也没排的票。 */
    public static Ticket empty() {
      return EMPTY;
    }

    /** 把几张票合成一张（兜底与判断是否还在排队时一起看）。 */
    public static Ticket combine(List<Ticket> tickets) {
      List<Entry> all = new ArrayList<>();
      for (Ticket ticket : tickets) {
        if (ticket != null) {
          all.addAll(ticket.entries);
        }
      }
      return all.isEmpty() ? EMPTY : new Ticket(List.copyOf(all));
    }

    /** 是否至少在一个附件上排进了动画。 */
    public boolean played() {
      return !entries.isEmpty();
    }

    /** 排进了几个附件。 */
    public int size() {
      return entries.size();
    }

    /** 还排在队里没轮到的个数。 */
    public int stuck() {
      int count = 0;
      for (Entry entry : entries) {
        if (stillQueued(entry)) {
          count++;
        }
      }
      return count;
    }

    /**
     * 把还排在队里没轮到的强行播放：带 {@code reset} 顶掉卡着的当前动画并清空那个附件的排队列表。
     *
     * @return 强行播放的个数
     */
    public int forceStuck() {
      int forced = 0;
      for (Entry entry : entries) {
        if (!stillQueued(entry)) {
          continue;
        }
        entry.animation().getOptions().setReset(true);
        entry.attachment().startAnimation(entry.animation());
        forced++;
      }
      return forced;
    }

    private static boolean stillQueued(Entry entry) {
      Attachment attachment = entry.attachment();
      if (!attachment.isAttached()) {
        return false;
      }
      for (Animation queued : attachment.getInternalState().nextAnimationQueue) {
        if (queued == entry.animation()) {
          return true;
        }
      }
      return false;
    }
  }

  /**
   * 在这些附件上按名字排队播放：取附件自己存的动画（名字不区分大小写），没有时与 TrainCarts 一样退回 TC 配置里的默认动画。
   *
   * @param options 动画名与速度等；{@code reset} 与 {@code queue} 由本方法定下（不 reset、排队）
   */
  public static Ticket playNamed(Collection<Attachment> targets, AnimationOptions options) {
    return playNamed(targets, options, true);
  }

  private static Ticket playNamed(
      Collection<Attachment> targets, AnimationOptions options, boolean useDefault) {
    if (targets == null || options == null || options.getName() == null) {
      return Ticket.EMPTY;
    }
    AnimationOptions queued = queuedOptions(options);
    Animation fallback = useDefault ? TCConfig.defaultAnimations.get(queued.getName()) : null;
    List<Ticket.Entry> entries = new ArrayList<>();
    for (Attachment target : targets) {
      if (target == null || !target.isAttached()) {
        continue;
      }
      Animation stored = storedAnimation(target, queued.getName());
      if (stored == null) {
        stored = fallback;
      }
      if (stored != null) {
        entries.add(enqueue(target, stored.clone().applyOptions(queued)));
      }
    }
    return new Ticket(List.copyOf(entries));
  }

  /** 附件自己存的这个名字的动画：先按原样找，找不到再不区分大小写找（选门附件时就是不区分大小写选的）。 */
  private static Animation storedAnimation(Attachment target, String name) {
    Map<String, Animation> animations = target.getInternalState().animations;
    Animation exact = animations.get(name);
    if (exact != null) {
      return exact;
    }
    for (Map.Entry<String, Animation> entry : animations.entrySet()) {
      if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name)) {
        return entry.getValue();
      }
    }
    return null;
  }

  /**
   * 在整列车上按名字排队播放：每节车厢附件树里带这个名字动画的附件都播（与 {@code MinecartGroup#playNamedAnimation} 相同的范围）； 整列车都没有时退回
   * TrainCarts 的默认动画，播在每节车厢的根附件上。
   */
  public static Ticket playNamed(MinecartGroup group, AnimationOptions options) {
    if (group == null) {
      return Ticket.EMPTY;
    }
    List<MinecartMember<?>> members = new ArrayList<>();
    for (MinecartMember<?> member : group) {
      members.add(member);
    }
    return playNamed(members, options);
  }

  /** 只在这几节车厢上按名字排队播放，规则同 {@link #playNamed(MinecartGroup, AnimationOptions)}（停车位置标只开部分车厢的门）。 */
  public static Ticket playNamed(List<MinecartMember<?>> members, AnimationOptions options) {
    if (members == null || options == null || options.getName() == null) {
      return Ticket.EMPTY;
    }
    List<Attachment> all = new ArrayList<>();
    List<Attachment> roots = new ArrayList<>();
    for (MinecartMember<?> member : members) {
      if (member == null
          || member.getAttachments() == null
          || !member.getAttachments().isAttached()) {
        continue;
      }
      Attachment root = member.getAttachments().getRootAttachment();
      if (root != null) {
        roots.add(root);
        all.addAll(HelperMethods.listAllAttachments(root));
      }
    }
    // 整列车的附件树里只在存了这个动画的附件上播；默认动画只退回到根附件上（与 TrainCarts 相同），不铺到每个附件。
    Ticket stored = playNamed(all, options, false);
    if (stored.played()) {
      return stored;
    }
    Animation fallback = TCConfig.defaultAnimations.get(options.getName());
    if (fallback == null) {
      return stored;
    }
    return play(roots, fallback.clone().applyOptions(options));
  }

  /** 在这些附件上排队播放给定的动画（各放一份拷贝）。 */
  public static Ticket play(Collection<Attachment> targets, Animation animation) {
    if (targets == null || animation == null) {
      return Ticket.EMPTY;
    }
    List<Ticket.Entry> entries = new ArrayList<>();
    for (Attachment target : targets) {
      if (target == null || !target.isAttached()) {
        continue;
      }
      // 直接改标志位：applyOptions 会把速度相乘、延迟相加，再套一次会把倒放（-1）变成正放。
      Animation copy = animation.clone();
      copy.getOptions().setReset(false);
      copy.getOptions().setQueue(true);
      entries.add(enqueue(target, copy));
    }
    return new Ticket(List.copyOf(entries));
  }

  /**
   * 排好的动画到 {@link #FORCE_AFTER_TICKS} 时还没轮到就强行播放。插件不可用（单元测试、停用中）时不排兜底。
   *
   * @return 同一张票，便于链式调用
   */
  public static Ticket withFallback(Ticket ticket) {
    if (ticket == null || !ticket.played()) {
      return ticket;
    }
    Plugin plugin;
    try {
      plugin = JavaPlugin.getProvidingPlugin(QueuedAnimations.class);
    } catch (RuntimeException | LinkageError ex) {
      return ticket;
    }
    if (plugin != null && plugin.isEnabled()) {
      Bukkit.getScheduler().runTaskLater(plugin, ticket::forceStuck, FORCE_AFTER_TICKS);
    }
    return ticket;
  }

  private static AnimationOptions queuedOptions(AnimationOptions options) {
    AnimationOptions queued = options.clone();
    queued.setReset(false);
    queued.setQueue(true);
    return queued;
  }

  private static Ticket.Entry enqueue(Attachment target, Animation animation) {
    target.startAnimation(animation);
    return new Ticket.Entry(target, animation);
  }
}
