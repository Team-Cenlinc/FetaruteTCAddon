package org.fetarute.fetaruteTCAddon.display.hud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

class HudTextWidthTest {

  private static String plain(Component component) {
    return PlainTextComponentSerializer.plainText().serialize(component);
  }

  @Test
  void measuresVanillaFontAdvances() {
    assertEquals(6, HudTextWidth.width("a"));
    assertEquals(2, HudTextWidth.width("i"));
    assertEquals(4, HudTextWidth.width(" "));
    assertEquals(27, HudTextWidth.width("新笛矢"), "汉字走 Unifont，每字 9 像素");
  }

  @Test
  void shortComponentsAreReturnedUntouched() {
    Component line = Component.text("下一站");
    assertSame(line, HudTextWidth.truncate(line, 120));
    assertSame(line, HudTextWidth.truncate(line, 0), "0 表示不限");
  }

  @Test
  void longComponentsAreCutWithAnEllipsisAndKeepTheirStyle() {
    Component line =
        Component.text("◘ ", NamedTextColor.DARK_PURPLE)
            .append(Component.text("Neo Fueya - Hor Huu", NamedTextColor.WHITE));

    Component cut = HudTextWidth.truncate(line, 60);

    assertTrue(HudTextWidth.width(cut) <= 60);
    assertTrue(plain(cut).startsWith("◘ Neo"));
    assertTrue(plain(cut).endsWith(HudTextWidth.ELLIPSIS));
    assertEquals(NamedTextColor.WHITE, cut.children().get(0).color(), "子组件的颜色保留");
  }

  @Test
  void boldTextIsOnePixelWiderPerGlyph() {
    Component bold = Component.text("abc").decorate(TextDecoration.BOLD);
    assertEquals(21, HudTextWidth.width(bold));
  }
}
