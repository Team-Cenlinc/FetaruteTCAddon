/**
 * 只读 HTTP 接口：把公开 API 的线路、车站、站牌与在线列车以 JSON 提供给外部的缓存中转（如 Cloudflare Worker）定时拉取。
 *
 * <p>网络收发、序列化与缓存全部在插件自己的后台线程完成，不在服务器主线程上做任何事；数据读的是公开 API 的内存快照。不输出玩家名等个人信息。
 */
package org.fetarute.fetaruteTCAddon.web;
