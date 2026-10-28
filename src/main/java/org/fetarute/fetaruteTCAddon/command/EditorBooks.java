package org.fetarute.fetaruteTCAddon.command;

import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.inventory.meta.BookMeta;

/**
 * 书与笔编辑器的页面读写：编组方案书、运行计划书等「发书 → 玩家编辑 → define 读回」的命令共用。
 *
 * <p>书页一律按纯文本读写；颜色与格式不进正文。
 */
final class EditorBooks {

  /** 发书时每页最多放几行，留出余量免得长行折行后溢出一页。 */
  static final int MAX_LINES_PER_PAGE = 11;

  private static final PlainTextComponentSerializer PLAIN_TEXT =
      PlainTextComponentSerializer.plainText();

  private EditorBooks() {}

  /**
   * 各页的纯文本，换行统一为 {@code \n}；空白页照样保留，页码与书里一致。
   *
   * @param meta 书
   * @return 各页文本
   */
  static List<String> pageTexts(BookMeta meta) {
    List<String> pages = new ArrayList<>();
    for (Component page : meta.pages()) {
      String text = page == null ? "" : PLAIN_TEXT.serialize(page);
      pages.add(text.replace("\r\n", "\n").replace('\r', '\n'));
    }
    return pages;
  }

  /**
   * 整本书按行展开，跳过空白页。
   *
   * @param meta 书
   * @return 各行
   */
  static List<String> lines(BookMeta meta) {
    List<String> lines = new ArrayList<>();
    for (String page : pageTexts(meta)) {
      if (!page.isEmpty()) {
        lines.addAll(List.of(page.split("\n", -1)));
      }
    }
    return lines;
  }

  /**
   * 把若干行按 {@link #MAX_LINES_PER_PAGE} 分页。
   *
   * @param lines 各行
   * @return 书页；没有内容时为一页空白
   */
  static List<Component> pages(List<String> lines) {
    List<Component> pages = new ArrayList<>();
    StringBuilder page = new StringBuilder();
    int count = 0;
    for (String line : lines) {
      if (count >= MAX_LINES_PER_PAGE) {
        pages.add(Component.text(page.toString()));
        page = new StringBuilder();
        count = 0;
      }
      if (count > 0) {
        page.append('\n');
      }
      page.append(line == null ? "" : line);
      count++;
    }
    if (page.length() > 0 || pages.isEmpty()) {
      pages.add(Component.text(page.toString()));
    }
    return pages;
  }
}
