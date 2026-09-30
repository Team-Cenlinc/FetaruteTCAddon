package org.fetarute.fetaruteTCAddon.dispatcher.sign.action;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import com.bergerkiller.bukkit.common.math.Matrix4x4;
import com.bergerkiller.bukkit.tc.attachments.animation.Animation;
import com.bergerkiller.bukkit.tc.attachments.animation.AnimationNode;
import com.bergerkiller.bukkit.tc.attachments.animation.AnimationOptions;
import com.bergerkiller.bukkit.tc.attachments.api.Attachment;
import com.bergerkiller.bukkit.tc.attachments.api.AttachmentInternalState;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

/**
 * 把本插件对 TrainCarts 动画队列的两条依赖钉成特征化测试；TrainCarts 升级改变它们时这里先报警。
 *
 * <p>2026-09-30：出库/首站预热以 {@code reset + speed=0} 播放门动画，留下一个永远播不完的当前动画，TrainCarts
 * 只在当前动画播完后才推进附件的动画队列， 显式带 {@code queue} 且不带 {@code reset} 的动画因此永远卡住；预热已删除。同时门动画选项同时置 {@code reset}
 * 与 {@code queue}， 而 {@code startAnimation} 先判 reset，{@code queue} 从不生效。
 */
class TrainCartsAnimationSemanticsTest {

  private static Animation twoSecondAnimation() {
    return new Animation(
        "doorL",
        new AnimationNode[] {
          new AnimationNode(new Vector(0, 0, 0), new Vector(0, 0, 0), true, 1.0),
          new AnimationNode(new Vector(1, 0, 0), new Vector(0, 0, 0), true, 1.0)
        });
  }

  private static boolean playsToTheEnd(double speed, int ticks) {
    Animation animation = twoSecondAnimation();
    AnimationOptions options = new AnimationOptions("doorL");
    options.setReset(true);
    options.setSpeed(speed);
    animation.applyOptions(options);
    animation.start();
    for (int i = 0; i < ticks; i++) {
      animation.update(0.05, new Matrix4x4());
    }
    return animation.hasReachedEnd();
  }

  @Test
  void normalSpeedAnimationReachesItsEnd() {
    assertTrue(playsToTheEnd(1.0, 200), "对照组：正常速度必须能播完，否则下一条断言没有意义");
  }

  @Test
  void zeroSpeedAnimationNeverReachesItsEnd() {
    assertFalse(playsToTheEnd(0.0, 20_000));
  }

  @Test
  void resetWinsOverQueueSoQueuedDoorAnimationsAreNeverQueued() {
    AttachmentInternalState state = new AttachmentInternalState();
    Attachment attachment = mock(Attachment.class, CALLS_REAL_METHODS);
    doReturn(state).when(attachment).getInternalState();

    Animation running = twoSecondAnimation();
    attachment.startAnimation(running);

    Animation door = twoSecondAnimation();
    door.applyOptions(AutoStationDoorController.doorAnimationOptions("doorL", 1.0));
    attachment.startAnimation(door);

    assertSame(door, state.currentAnimation, "reset 先判：直接顶掉当前动画");
    assertTrue(state.nextAnimationQueue.isEmpty(), "queue 分支从未走到");
  }

  @Test
  void queuedAnimationWithoutResetWaitsBehindTheRunningOne() {
    AttachmentInternalState state = new AttachmentInternalState();
    Attachment attachment = mock(Attachment.class, CALLS_REAL_METHODS);
    doReturn(state).when(attachment).getInternalState();

    Animation running = twoSecondAnimation();
    attachment.startAnimation(running);

    Animation queued = twoSecondAnimation();
    AnimationOptions options = new AnimationOptions("doorL");
    options.setQueue(true);
    queued.applyOptions(options);
    attachment.startAnimation(queued);

    assertSame(running, state.currentAnimation);
    assertEquals(1, state.nextAnimationQueue.size());
  }
}
