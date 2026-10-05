import com.things.link.assistant.infrastructure.transport.InternalAnalysisResultDecoder;
import java.util.Set;
import java.util.UUID;

/** 读取合成标准输入并仅输出固定校验状态，避免生成内容进入测试输出。 */
class InternalAnalysisResultProbe {
    public static void main(String[] args) throws Exception {
        var result = InternalAnalysisResultDecoder.decode(System.in.readNBytes(24577),
                UUID.fromString("019c1234-5678-7890-8123-456789abcdef"), 2, args[0], args[1], args[2], args[3],
                Set.of("e-device", "e-alarm", "e-property-1"), args.length == 5 ? args[4] : null);
        System.out.println(result.qualification() + ":" + result.status() + ":"
                + (result.usage() == null ? "UNKNOWN_USAGE" : result.usage().totalTokens()));
    }
}
