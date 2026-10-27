package org.fetarute.fetaruteTCAddon.web;

/**
 * 回应依赖的数据还没准备好（或生成失败）。
 *
 * <p>路由把这类失败转换成 {@code 503} 与 {@code no-store}，而不是 {@code 500}：它是暂态的服务状态，不是请求错误，也不该被缓存或让中转把它当成 内容。
 */
final class WebUnavailableException extends RuntimeException {

  WebUnavailableException(String message) {
    super(message);
  }
}
