package org.fetarute.fetaruteTCAddon.web.gtfs;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 确定性 zip：条目顺序由调用方给定，条目时刻写死，内容不变时整包逐字节一致。
 *
 * <p>zip 的 ETag 按 {@code feed_version}（内容摘要）算，不直接按字节算；但中转会用 {@code If-None-Match}
 * 复验、也可能有人把它当文件缓存，整包可复现能让 复验与差分都简单。条目时刻用 {@link ZipEntry#setTimeLocal(LocalDateTime)} 固定为本地时间，避免
 * {@code setTime} 按服务器时区换算后跟着机器变。
 */
public final class GtfsZip {

  /** 条目时刻固定为这个本地时刻；只影响 zip 头，不参与任何业务。 */
  private static final LocalDateTime FIXED_TIME = LocalDateTime.of(2020, 1, 1, 0, 0, 0);

  private GtfsZip() {}

  /**
   * 一个 zip 条目。
   *
   * @param name 包内路径（如 {@code stops.txt}）
   * @param body 内容
   */
  public record Entry(String name, byte[] body) {

    public Entry {
      name = Objects.requireNonNull(name, "name");
      body = Objects.requireNonNull(body, "body").clone();
    }

    /** 返回副本：条目一旦建好就不再变，调用方拿到的数组不会反向改到 feed。 */
    @Override
    public byte[] body() {
      return body.clone();
    }
  }

  /**
   * 打成一个 zip。
   *
   * @param entries 条目，按给定顺序写入
   */
  public static byte[] write(List<Entry> entries) {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(buffer)) {
      for (Entry entry : entries) {
        ZipEntry zipEntry = new ZipEntry(entry.name());
        zipEntry.setTimeLocal(FIXED_TIME);
        zip.putNextEntry(zipEntry);
        zip.write(entry.body());
        zip.closeEntry();
      }
    } catch (IOException ex) {
      // 写内存数组不会真的抛 IO 异常；真抛了也只能是编码层的问题
      throw new UncheckedIOException("GTFS zip 写出失败", ex);
    }
    return buffer.toByteArray();
  }
}
