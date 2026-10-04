package com.things.link.bootstrap.fixture;

/** 真实浏览器旅程与Cookie/WS使用同一个独立测试Origin，不停止开发者已有Vite服务。 */
public final class WebAppJourneyOrigin {
    /** 纯配置工具禁止实例化；端口只来自测试进程环境，不从业务请求推断Origin。 */
    private WebAppJourneyOrigin() {
    }

    /** @return 与Node旅程同源的合法loopback测试Origin，非法配置在启动前拒绝 */
    public static String origin() {
        String port = System.getenv().getOrDefault("WEBAPP_JOURNEY_PORT", "3007");
        if (!port.matches("[1-9][0-9]{0,4}") || Integer.parseInt(port) > 65535) {
            throw new IllegalArgumentException("非法WebApp旅程端口");
        }
        return "http://localhost:" + port;
    }
}
