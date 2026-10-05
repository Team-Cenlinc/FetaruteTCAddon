package org.fetarute.fetaruteTCAddon.display.pids.render;

import static org.fetarute.fetaruteTCAddon.display.pids.render.PidsBulletinTypesetter.CHIP;
import static org.fetarute.fetaruteTCAddon.display.pids.render.PidsBulletinTypesetter.INSET;
import static org.fetarute.fetaruteTCAddon.display.pids.render.PidsBulletinTypesetter.LANDSCAPE_BODY_TOP;
import static org.fetarute.fetaruteTCAddon.display.pids.render.PidsBulletinTypesetter.LANDSCAPE_SECONDARY_TOP;
import static org.fetarute.fetaruteTCAddon.display.pids.render.PidsBulletinTypesetter.LANDSCAPE_TITLE;
import static org.fetarute.fetaruteTCAddon.display.pids.render.PidsBulletinTypesetter.LANDSCAPE_TITLE_X;
import static org.fetarute.fetaruteTCAddon.display.pids.render.PidsBulletinTypesetter.LANDSCAPE_TOP;
import static org.fetarute.fetaruteTCAddon.display.pids.render.PidsBulletinTypesetter.PORTRAIT_CHIP_TOP;
import static org.fetarute.fetaruteTCAddon.display.pids.render.PidsBulletinTypesetter.PORTRAIT_TOP;

import java.util.Objects;
import org.fetarute.fetaruteTCAddon.display.pids.layout.PidsLayout;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsBulletinView;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsView.Names;

/**
 * 公告页的画法（排版见 {@link PidsBulletinTypesetter}）。
 *
 * <ul>
 *   <li>横屏：左上图标块，其右中文标题、其下英文标题；右上角标签（重要公告用警示色），分页时页码写在英文标题那一行右端；正文占满整屏宽。
 *   <li>竖屏：顶部图标块与标签，其下标题与正文；分页时页码写在右下角。
 * </ul>
 */
final class PidsBulletinPainter {

  /** 标签：中文 12 号、英文 10 号上下叠放。 */
  static final int LABEL = 12;

  static final int LABEL_SECONDARY = 10;

  /** 竖屏页码离正文区底边的高度。 */
  private static final int PORTRAIT_PAGE_LIFT = 16;

  /** 图标在图标块里的边距（16×16 图标放在 24×24 块中央）。 */
  private static final int ICON_INSET = 4;

  private final PidsRenderer.Painter p;
  private final PidsTheme theme;

  PidsBulletinPainter(PidsRenderer.Painter painter) {
    this.p = Objects.requireNonNull(painter, "painter");
    this.theme = painter.theme();
  }

  /** 标签宽度：中文与英文取宽者。 */
  static int labelWidth(PidsRenderer.Painter p, Names label) {
    return Math.max(
        p.width(label.primary(), LABEL), p.regularWidth(label.secondary(), LABEL_SECONDARY));
  }

  void draw(PidsLayout layout, PidsBulletinView view) {
    if (PidsBulletinTypesetter.landscape(layout)) {
      drawLandscape(layout, view);
    } else {
      drawPortrait(layout, view);
    }
  }

  private void drawLandscape(PidsLayout layout, PidsBulletinView view) {
    int right = layout.width() - INSET;
    drawChip(view, INSET, LANDSCAPE_TOP);
    Names label = view.label();
    p.textRight(label.primary(), LABEL, right, LANDSCAPE_TOP + 1, labelColor(view));
    p.regularRight(label.secondary(), LABEL_SECONDARY, right, LANDSCAPE_TOP + 15, theme.muted());

    int titleRoom = right - labelWidth(p, label) - INSET - LANDSCAPE_TITLE_X;
    p.text(
        p.ellipsize(view.title().primary(), LANDSCAPE_TITLE, titleRoom),
        LANDSCAPE_TITLE,
        LANDSCAPE_TITLE_X,
        LANDSCAPE_TOP,
        theme.text());
    int secondaryRoom = right - LANDSCAPE_TITLE_X;
    String page = view.pageLabel();
    if (!page.isEmpty()) {
      p.regularRight(page, LABEL_SECONDARY, right, LANDSCAPE_SECONDARY_TOP, theme.muted());
      secondaryRoom -= p.regularWidth(page, LABEL_SECONDARY) + INSET;
    }
    p.regular(
        p.ellipsizeWords(view.title().secondary(), LABEL_SECONDARY, secondaryRoom),
        LABEL_SECONDARY,
        LANDSCAPE_TITLE_X,
        LANDSCAPE_SECONDARY_TOP,
        theme.muted(),
        false);
    drawBlocks(view.page(), LANDSCAPE_BODY_TOP);
  }

  private void drawPortrait(PidsLayout layout, PidsBulletinView view) {
    drawChip(view, INSET, PORTRAIT_CHIP_TOP);
    int labelX = INSET + CHIP + INSET;
    p.text(view.label().primary(), LABEL, labelX, PORTRAIT_CHIP_TOP + 1, labelColor(view));
    p.regular(
        view.label().secondary(),
        LABEL_SECONDARY,
        labelX,
        PORTRAIT_CHIP_TOP + 15,
        theme.muted(),
        false);
    drawBlocks(view.page(), PORTRAIT_TOP);
    String page = view.pageLabel();
    if (!page.isEmpty()) {
      p.regularRight(
          page,
          LABEL,
          layout.width() - INSET,
          PidsBulletinTypesetter.contentBottom(layout) - PORTRAIT_PAGE_LIFT,
          theme.muted());
    }
  }

  private int labelColor(PidsBulletinView view) {
    return view.important() ? theme.amber() : theme.muted();
  }

  private void drawBlocks(PidsBulletinTypesetter.Page page, int top) {
    int y = top;
    for (PidsBulletinTypesetter.Block block : page.blocks()) {
      y += block.gapBefore();
      int lineTop = y;
      for (String line : block.lines()) {
        if (block.primary()) {
          p.text(line, block.size(), INSET, lineTop, theme.text());
        } else {
          p.regular(line, block.size(), INSET, lineTop, theme.muted(), false);
        }
        lineTop += block.pitch();
      }
      y += block.lines().size() * block.pitch() - (block.pitch() - block.size());
    }
  }

  /** 图标块：一般公告钢蓝底喇叭，重要公告警示色底感叹号；图标颜色按底色取深浅。 */
  private void drawChip(PidsBulletinView view, int x, int y) {
    int block = view.important() ? theme.amber() : theme.info();
    p.fill(x, y, CHIP, CHIP, block);
    int rgb = PidsTheme.textOn(block);
    int ix = x + ICON_INSET;
    int iy = y + ICON_INSET;
    if (view.important()) {
      p.fill(ix + 6, iy + 1, 4, 9, rgb);
      p.fill(ix + 6, iy + 12, 4, 3, rgb);
      return;
    }
    // 向右的喇叭：尾部、喇叭口、手柄与两道声波
    p.fill(ix + 1, iy + 6, 3, 4, rgb);
    p.triangle(new int[] {ix + 4, ix + 12, ix + 12}, new int[] {iy + 6, iy + 2, iy + 14}, rgb);
    p.triangle(new int[] {ix + 4, ix + 12, ix + 4}, new int[] {iy + 6, iy + 14, iy + 10}, rgb);
    p.fill(ix + 5, iy + 10, 2, 4, rgb);
    p.fill(ix + 13, iy + 4, 1, 2, rgb);
    p.fill(ix + 14, iy + 6, 1, 4, rgb);
    p.fill(ix + 13, iy + 10, 1, 2, rgb);
  }
}
