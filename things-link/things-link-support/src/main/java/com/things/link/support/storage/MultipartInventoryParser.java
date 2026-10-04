package com.things.link.support.storage;

import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/** 仅解析multipart盘点身份；容忍无关StorageClass缺省，不修改通用SDK或伪造其值。 */
final class MultipartInventoryParser {
    /** 原始XML最大字节数。 */
    private static final int MAX_XML = 2 * 1024 * 1024;
    /** 任一元素直接文本的UTF8最大长度。 */
    private static final int MAX_FIELD = 16_384;
    /** 嵌套元素最大深度。 */
    private static final int MAX_DEPTH = 16;
    /** 单页最大上传条目数。 */
    private static final int MAX_ITEMS = 500;
    /** 需要唯一解释的根级分页字段。 */
    private static final Set<String> FIELDS = Set.of("Bucket", "EncodingType", "KeyMarker", "UploadIdMarker",
            "NextKeyMarker", "NextUploadIdMarker", "IsTruncated", "MaxUploads", "Prefix", "Delimiter");
    /** S3标准XML命名空间。 */
    private static final String NAMESPACE = "http://s3.amazonaws.com/doc/2006-03-01/";
    /** 工具类不允许实例化。 */
    private MultipartInventoryParser() { }
    /**
     * 精确上传身份，objectName已按EncodingType完成一次严格解码。
     * @param objectName 对象键
     * @param uploadId 不进行URL解码的上传ID
     */
    record Entry(String objectName, String uploadId) { }
    /**
     * 有界分页投影，不证明此后不会有迟到上传。
     * @param bucketName 响应桶
     * @param encodingType 原始键编码类型
     * @param nextKeyMarker 已解码provider键位置
     * @param nextUploadIdMarker 原样provider上传位置
     * @param isTruncated 是否存在下页
     * @param uploads 本页上传身份
     */
    record Page(String bucketName, String encodingType, String nextKeyMarker, String nextUploadIdMarker,
                boolean isTruncated, List<Entry> uploads) {
        /** 固定页内条目，不向调用方暴露可变列表。 */
        Page { uploads = List.copyOf(uploads); }
    }
    /** 解析并关闭输入流；禁用DTD与外部实体，任何不可信结构错误统一失败。 */
    static Page parse(InputStream source) {
        XMLStreamReader reader = null;
        LimitedInput input = new LimitedInput(source);
        try (input) {
            XMLInputFactory factory = XMLInputFactory.newFactory();
            factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
            factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
            factory.setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, false);
            factory.setXMLResolver((publicId, systemId, baseUri, namespace) -> {
                throw new XMLStreamException("XML external resolution forbidden");
            });
            reader = factory.createXMLStreamReader(input, "UTF-8");
            Deque<Element> stack = new ArrayDeque<>();
            Map<String, String> fields = new HashMap<>();
            List<Entry> raw = new ArrayList<>();
            Map<String, String> upload = null;
            String namespace = null;
            boolean rootSeen = false;
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.DTD || event == XMLStreamConstants.ENTITY_REFERENCE) { throw invalid(); }
                if (event == XMLStreamConstants.START_ELEMENT) {
                    String name = reader.getLocalName();
                    String ns = reader.getNamespaceURI() == null ? "" : reader.getNamespaceURI();
                    if (stack.isEmpty()) {
                        if (rootSeen || !"ListMultipartUploadsResult".equals(name)
                                || !(ns.isEmpty() || NAMESPACE.equals(ns))) { throw invalid(); }
                        rootSeen = true;
                        namespace = ns;
                    } else {
                        if (!namespace.equals(ns)) { throw invalid(); }
                        stack.peek().complex = true;
                    }
                    if (stack.size() >= MAX_DEPTH) { throw invalid(); }
                    if (stack.size() == 1 && "Upload".equals(name)) {
                        if (raw.size() >= MAX_ITEMS) { throw invalid(); }
                        upload = new HashMap<>();
                    }
                    stack.push(new Element(name));
                } else if (event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA
                        || event == XMLStreamConstants.SPACE) {
                    if (!stack.isEmpty()) { stack.peek().append(reader.getText()); }
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    Element element = stack.pop();
                    String text = element.text.toString();
                    if (text.getBytes(StandardCharsets.UTF_8).length > MAX_FIELD) { throw invalid(); }
                    if (stack.size() == 1 && FIELDS.contains(element.name)) {
                        if (element.complex || fields.putIfAbsent(element.name, text) != null) { throw invalid(); }
                    } else if (stack.size() == 2 && "Upload".equals(stack.peek().name)
                            && ("Key".equals(element.name) || "UploadId".equals(element.name))) {
                        if (element.complex || upload == null || upload.putIfAbsent(element.name, text) != null) {
                            throw invalid();
                        }
                    } else if (stack.size() == 1 && "Upload".equals(element.name)) {
                        if (upload == null) { throw invalid(); }
                        raw.add(new Entry(required(upload, "Key"), required(upload, "UploadId")));
                        upload = null;
                    }
                }
            }
            if (!rootSeen || !stack.isEmpty()) { throw invalid(); }
            String bucket = required(fields, "Bucket");
            String truncated = required(fields, "IsTruncated");
            if (!"true".equals(truncated) && !"false".equals(truncated)) { throw invalid(); }
            String encoding = fields.get("EncodingType");
            if (encoding != null && !encoding.isEmpty() && !"url".equals(encoding)) { throw invalid(); }
            List<Entry> entries = new ArrayList<>();
            Set<Entry> unique = new HashSet<>();
            for (Entry item : raw) {
                Entry decoded = new Entry(decode(item.objectName(), encoding), item.uploadId());
                if (!unique.add(decoded)) { throw invalid(); }
                entries.add(decoded);
            }
            return new Page(bucket, encoding, decode(fields.get("NextKeyMarker"), encoding),
                    fields.get("NextUploadIdMarker"), "true".equals(truncated), entries);
        } catch (IOException | XMLStreamException | RuntimeException exception) {
            if (input.transportFailed) {
                throw new VersionedStorageException(VersionedStorageException.Reason.UNAVAILABLE, false);
            }
            throw invalid();
        } finally {
            if (reader != null) {
                try { reader.close(); } catch (XMLStreamException ignored) { /* 输入流已由资源范围关闭。 */ }
            }
        }
    }
    /** 必填身份不得缺失或退化为空白值。 */
    private static String required(Map<String, String> fields, String key) {
        String value = fields.get(key);
        if (value == null || value.isBlank()) { throw invalid(); }
        return value;
    }
    /** S3百分号编码不是表单编码，literal加号保留，畸形UTF8不得替换为问号。 */
    private static String decode(String value, String encoding) throws IOException {
        if (value == null || !"url".equals(encoding)) { return value; }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (int i = 0; i < value.length();) {
            if (value.charAt(i) == '%') {
                if (i + 2 >= value.length()) { throw invalid(); }
                int high = asciiHex(value.charAt(i + 1));
                int low = asciiHex(value.charAt(i + 2));
                if (high < 0 || low < 0) { throw invalid(); }
                bytes.write(high * 16 + low);
                i += 3;
            } else {
                int point = value.codePointAt(i);
                bytes.writeBytes(new String(Character.toChars(point)).getBytes(StandardCharsets.UTF_8));
                i += Character.charCount(point);
            }
        }
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
    }
    /** 百分号编码只允许ASCII十六进制，拒绝全角等Unicode数字。 */
    private static int asciiHex(char value) {
        if (value >= '0' && value <= '9') { return value - '0'; }
        if (value >= 'a' && value <= 'f') { return value - 'a' + 10; }
        if (value >= 'A' && value <= 'F') { return value - 'A' + 10; }
        return -1;
    }
    /** 构造不包含XML原文或解析位置的固定错误。 */
    private static VersionedStorageException invalid() {
        return new VersionedStorageException(VersionedStorageException.Reason.INTEGRITY, false);
    }
    /** 单个元素的有界文本及是否包含子节点。 */
    private static final class Element {
        /** 元素局部名称。 */
        private final String name;
        /** 当前元素直接文本。 */
        private final StringBuilder text = new StringBuilder();
        /** 文本累计UTF8字节。 */
        private int bytes;
        /** 标记身份字段是否含嵌套节点。 */
        private boolean complex;
        /** 创建空的元素累积器。 */
        Element(String name) { this.name = name; }
        /** 每次片段追加前检查字符及字节预算。 */
        void append(String value) {
            bytes += value.getBytes(StandardCharsets.UTF_8).length;
            if (bytes > MAX_FIELD) { throw invalid(); }
            text.append(value);
        }
    }
    /** 对所有SDK传回的原始XML实施硬字节上限。 */
    private static final class LimitedInput extends FilterInputStream {
        /** 已读取的原始字节数。 */
        private int consumed;
        /** 保存真实底层I/O失败类别，不保留可能携带地址的异常正文。 */
        private boolean transportFailed;
        /** 包装调用方响应流。 */
        LimitedInput(InputStream source) { super(source); }
        /** 单字节读取也不得绕过预算。 */
        @Override public int read() throws IOException {
            int result;
            try { result = in.read(); } catch (IOException exception) {
                transportFailed = true;
                throw exception;
            }
            if (result >= 0 && ++consumed > MAX_XML) { throw new IOException("XML budget exceeded"); }
            return result;
        }
        /** 最多读取剩余预算加一字节，以明确拒绝超限输入。 */
        @Override public int read(byte[] buffer, int offset, int length) throws IOException {
            int read;
            try { read = in.read(buffer, offset, Math.min(length, MAX_XML - consumed + 1)); }
            catch (IOException exception) {
                transportFailed = true;
                throw exception;
            }
            if (read > 0) {
                consumed += read;
                if (consumed > MAX_XML) { throw new IOException("XML budget exceeded"); }
            }
            return read;
        }
    }
}
