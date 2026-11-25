package com.smartparking.iot.codec;

import java.util.Optional;

/**
 * 解析后的上行帧。上行没有命令码，按数据长度区分：1 字节为回显、2 字节且首字节 {@code CF} 为版本、3 字节为状态，
 * 其余为 {@link Unknown}。
 */
public sealed interface UplinkFrame {

    /** RS485 地址 */
    int address();

    /** 原始帧，契约 {@code RawFrame} 格式 */
    String rawFrame();

    /**
     * 状态帧（8 字节）：闸杆状态 + 动作原因 + 车位。
     *
     * @param occupied 车位是否有车；{@code 00} 无车、{@code 11} 有车，其余（含充电相关的 {@code 22}）为 {@code null}
     */
    record Status(
            int address,
            int statusCode,
            GateStatus status,
            int reasonCode,
            TriggerSource trigger,
            int carCode,
            Boolean occupied,
            String rawFrame)
            implements UplinkFrame {

        /** 本帧表达的异常；电机故障优先于原因码 */
        public Optional<GateFault> fault() {
            if (status == GateStatus.FAULT) {
                return Optional.of(GateFault.MOTOR_FAULT);
            }
            return switch (trigger) {
                case OBSTRUCTED -> Optional.of(GateFault.OBSTRUCTED);
                case INFRARED -> Optional.of(GateFault.INFRARED);
                default -> Optional.empty();
            };
        }
    }

    /** 版本帧（7 字节）：{@code CF} + 版本号 */
    record Version(int address, int version, String rawFrame) implements UplinkFrame {}

    /**
     * 回显帧（6 字节）：闸板把开闸 / 关闸命令原样回送，<strong>不能当作执行成功</strong>。
     *
     * @param command 命令码不在码表内时为空
     */
    record Echo(int address, int commandCode, Optional<GateCommand> command, String rawFrame) implements UplinkFrame {}

    /** 首尾与长度合法，但数据段不符合任何已知类型 */
    record Unknown(int address, String rawFrame) implements UplinkFrame {}
}
