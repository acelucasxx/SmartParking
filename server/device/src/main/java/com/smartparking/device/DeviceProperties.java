package com.smartparking.device;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 设备域配置，前缀 {@code smartparking.device}。
 *
 * @param activateCodeKey 激活码哈希与印刷数据加密的主密钥，至少 32 字节。换掉后已入库设备的激活码全部失效，只能逐台重置
 * @param qrUrlTemplate 机身二维码内容，必须含 {@code {sn}}。小程序“扫普通链接二维码”的规则（EXT-13）确定后配置；印刷文件只能导出一次，配错只能逐台重置
 */
@ConfigurationProperties("smartparking.device")
public record DeviceProperties(String activateCodeKey, String qrUrlTemplate) {

    public static final String SN_PLACEHOLDER = "{sn}";

    public DeviceProperties {
        if (qrUrlTemplate == null || !qrUrlTemplate.contains(SN_PLACEHOLDER)) {
            throw new IllegalArgumentException("smartparking.device.qr-url-template 必须配置且包含 {sn}");
        }
    }

    public String qrContent(String sn) {
        return qrUrlTemplate.replace(SN_PLACEHOLDER, sn);
    }
}
