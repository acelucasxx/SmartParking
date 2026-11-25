/**
 * 道闸 RS485 协议（《智慧机闸 RS485 协议 20240914》V4.0）的帧编解码，规则见 PRD 8.2、8.3。
 *
 * <p>纯函数，不依赖 Spring，云端与 DTU 模拟器共用这一套实现。要点：
 *
 * <ul>
 *   <li>上下行结构不同，同一长度含义不同，解析必须显式指定方向，见 {@link com.smartparking.iot.codec.Rs485Codec}。
 *   <li>协议无 CRC，只校验长度、首尾标志；结构不合法抛 {@link com.smartparking.iot.codec.ProtocolException}，
 *       状态码、原因码、车位码查不到时记为未知，不抛异常。
 *   <li>查询指令的数据位固定为 {@code C0}（充电板待机），发错会导致落杆静默失效。
 *   <li>回显帧只说明命令到了串口，不能当作执行成功。
 * </ul>
 */
package com.smartparking.iot.codec;
