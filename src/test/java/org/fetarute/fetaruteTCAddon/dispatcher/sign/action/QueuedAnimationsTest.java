package org.fetarute.fetaruteTCAddon.dispatcher.sign.action;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import com.bergerkiller.bukkit.tc.attachments.animation.Animation;
import com.bergerkiller.bukkit.tc.attachments.animation.AnimationNode;
import com.bergerkiller.bukkit.tc.attachments.animation.AnimationOptions;
import com.bergerkiller.bukkit.tc.attachments.api.Attachment;
import com.bergerkiller.bukkit.tc.attachments.api.AttachmentInternalState;
import java.util.List;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("排队播放模型动画（不 reset）")
class QueuedAnimationsTest {

  private static Animation animation(String name) {
    return new Animation(
        name,
        new AnimationNode[] {
          new AnimationNode(new Vector(0, 0, 0), new Vector(0, 0, 0), true, 1.0),
          new AnimationNode(new Vector(1, 0, 0), new Vector(0, 0, 0), true, 1.0)
        });
  }

  /** 一个带 doorL 与 ptg5 动画、已挂载的附件。 */
  private static Attachment attachment(AttachmentInternalState state) {
    state.animations.put("doorL", animation("doorL"));
    state.animations.put("ptg5", animation("ptg5"));
    Attachment attachment = mock(Attachment.class, CALLS_REAL_METHODS);
    doReturn(state).when(attachment).getInternalState();
    doReturn(true).when(attachment).isAttached();
    return attachment;
  }

  @Test
  @DisplayName("没有正在播的动画时直接从头播")
  void playsRightAwayWhenIdle() {
    AttachmentInternalState state = new AttachmentInternalState();
    Attachment door = attachment(state);

    QueuedAnimations.Ticket ticket =
        QueuedAnimations.playNamed(List.of(door), new AnimationOptions("doorL"));

    assertTrue(ticket.played());
    assertEquals("doorL", state.currentAnimation.getOptions().getName());
    assertEquals(0, ticket.stuck());
  }

  @Test
  @DisplayName("牌子排进去的动画不会被清掉：车门排在它后面")
  void doesNotClearAnimationsQueuedBySigns() {
    AttachmentInternalState state = new AttachmentInternalState();
    Attachment door = attachment(state);
    Animation running = animation("doorL");
    door.startAnimation(running);
    Animation fromSign = animation("doorL");
    AnimationOptions signOptions = new AnimationOptions("doorL");
    signOptions.setQueue(true);
    fromSign.applyOptions(signOptions);
    door.startAnimation(fromSign);

    AnimationOptions close = AutoStationDoorController.doorAnimationOptions("doorL", -1.0);
    QueuedAnimations.Ticket ticket = QueuedAnimations.playNamed(List.of(door), close);

    assertSame(running, state.currentAnimation);
    assertEquals(2, state.nextAnimationQueue.size());
    assertSame(fromSign, state.nextAnimationQueue.get(0));
    assertTrue(state.nextAnimationQueue.get(1).getOptions().isReversed(), "倒放关门保持倒放");
    assertFalse(state.nextAnimationQueue.get(1).getOptions().getReset());
    assertEquals(1, ticket.stuck());
  }

  @Test
  @DisplayName("卡在放不完的动画后面：兜底时强行播放")
  void forcesThroughAStuckAnimation() {
    AttachmentInternalState state = new AttachmentInternalState();
    Attachment door = attachment(state);
    Animation looped = animation("idle");
    AnimationOptions loop = new AnimationOptions("idle");
    loop.setLooped(true);
    looped.applyOptions(loop);
    door.startAnimation(looped);

    QueuedAnimations.Ticket ticket =
        QueuedAnimations.playNamed(List.of(door), new AnimationOptions("doorL"));
    assertEquals(1, ticket.stuck());

    assertEquals(1, ticket.forceStuck());
    assertEquals("doorL", state.currentAnimation.getOptions().getName());
    assertTrue(state.nextAnimationQueue.isEmpty());
    assertEquals(0, ticket.stuck());
  }

  @Test
  @DisplayName("直接排一个动画实例：倒放不会被速度相乘翻成正放")
  void playKeepsTheDirection() {
    AttachmentInternalState state = new AttachmentInternalState();
    Attachment door = attachment(state);
    door.startAnimation(animation("other"));
    Animation close = animation("doorL");
    AnimationOptions reverse = new AnimationOptions("doorL");
    reverse.setSpeed(-1.0);
    close.applyOptions(reverse);

    QueuedAnimations.play(List.of(door), close);

    assertTrue(state.nextAnimationQueue.get(0).getOptions().isReversed());
  }

  @Test
  @DisplayName("超级电容受电弓：牌子升起（或正排着升弓）才算升着，已经排了降弓就不算")
  void detectsARaisedPantograph() {
    AttachmentInternalState state = new AttachmentInternalState();
    Attachment pantograph = attachment(state);
    assertFalse(SupercapPantograph.raised(pantograph, "ptg5"), "从没升过");

    Animation up = animation("ptg5");
    pantograph.startAnimation(up);
    assertTrue(SupercapPantograph.raised(pantograph, "ptg5"), "牌子正放 ptg5");

    AnimationOptions down = new AnimationOptions("ptg5");
    down.setSpeed(-1.0);
    QueuedAnimations.playNamed(List.of(pantograph), down);
    assertFalse(SupercapPantograph.raised(pantograph, "ptg5"), "已经排了降弓");
  }
}
