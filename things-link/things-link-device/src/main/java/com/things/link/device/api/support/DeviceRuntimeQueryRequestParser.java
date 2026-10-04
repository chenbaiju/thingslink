package com.things.link.device.api.support;
import org.springframework.stereotype.Component;
/** Console保留原装配名称，解析委托中性application内核，公开入口无需跨域引用API包。 */
@Component
public class DeviceRuntimeQueryRequestParser extends com.things.link.device.application.RuntimeDeviceQueryParser { }
