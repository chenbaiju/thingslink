package com.things.link.support.storage;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 真实S3分片身份之外的空字段可兼容，XML扩展与不完整身份必须拒绝。 */
class MultipartInventoryParserTests {
    /** 固定S3根，不让SDK无关StorageClass必填约束影响回收身份。 */
    private static final String OPEN = "<ListMultipartUploadsResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">";
    /** 固定测试XML闭合。 */
    private static final String CLOSE = "</ListMultipartUploadsResult>";

    /** 空StorageClass合法，编码键中的百分号和加号不能改变精确对象身份。 */
    @Test
    void acceptsEmptyAncillaryFieldsAndDecodesOnlyKeys() {
        var page = parse(OPEN + "<Bucket>test-bucket</Bucket><EncodingType>url</EncodingType>"
                + "<IsTruncated>true</IsTruncated><NextKeyMarker>attempt%2F%E5%9B%BA%E4%BB%B6%20%2B</NextKeyMarker>"
                + "<NextUploadIdMarker>id+%2F</NextUploadIdMarker><Upload>"
                + "<Key>attempt%2F%E5%9B%BA%E4%BB%B6%20%2B</Key><UploadId>id+%2F</UploadId>"
                + "<StorageClass></StorageClass></Upload>" + CLOSE);
        assertThat(page.bucketName()).isEqualTo("test-bucket");
        assertThat(page.nextKeyMarker()).isEqualTo("attempt/固件 +");
        assertThat(page.uploads()).hasSize(1);
        assertThat(page.uploads().getFirst().objectName()).isEqualTo("attempt/固件 +");
        assertThat(page.uploads().getFirst().uploadId()).isEqualTo("id+%2F");
        assertThat(page.nextUploadIdMarker()).isEqualTo("id+%2F");
        assertThatThrownBy(() -> page.uploads().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    /** 不是ASCII十六进制或不是合法UTF8的百分号序列必须拒绝。 */
    @Test
    void rejectsNonAsciiHexAndMalformedUtf8() {
        for (String key : new String[] {"%４１", "%FF", "%C0%AF", "%0", "%GG"}) {
            rejected(document("<EncodingType>url</EncodingType><Upload><Key>" + key
                    + "</Key><UploadId>id</UploadId></Upload>"));
        }
    }

    /** DTD及外部实体即使内容看似有效也不能进入任何资源解析。 */
    @Test
    void rejectsDtdAndExternalEntities() {
        rejected("<!DOCTYPE ListMultipartUploadsResult [<!ENTITY x SYSTEM 'file:///nonexistent-test-only'>]>"
                + OPEN + "<Bucket>&x;</Bucket><IsTruncated>false</IsTruncated>" + CLOSE);
        rejected("<!DOCTYPE ListMultipartUploadsResult [<!ENTITY x 'test-bucket'>]>"
                + OPEN + "<Bucket>&x;</Bucket><IsTruncated>false</IsTruncated>" + CLOSE);
    }

    /** 桶、截断和条目身份缺失或重复不得被解析器最后值覆盖。 */
    @Test
    void rejectsMissingAndDuplicateIdentity() {
        rejected(OPEN + "<IsTruncated>false</IsTruncated>" + CLOSE);
        rejected(OPEN + "<Bucket>test-bucket</Bucket>" + CLOSE);
        rejected(OPEN + "<Bucket>a</Bucket><Bucket>b</Bucket><IsTruncated>false</IsTruncated>" + CLOSE);
        rejected(document("<Upload><Key>one</Key></Upload>"));
        rejected(document("<Upload><Key>one</Key><Key>two</Key><UploadId>id</UploadId></Upload>"));
        rejected(document("<Upload><Key>one</Key><UploadId>id</UploadId><UploadId>other</UploadId></Upload>"));
        rejected(OPEN + "<Bucket>a</Bucket><IsTruncated>maybe</IsTruncated>" + CLOSE);
    }

    /** 身份字段不能借子节点合并字符来构造与原文不同的对象。 */
    @Test
    void rejectsNestedIdentityValues() {
        rejected(document("<Upload><Key>one<Other>two</Other></Key><UploadId>id</UploadId></Upload>"));
    }

    /** 未使用的附属结构也受深度上限控制。 */
    @Test
    void limitsNestedDepth() {
        rejected(document("<Other>".repeat(17) + "x" + "</Other>".repeat(17)));
    }

    /** 超大单字段不能绕过整页上限消耗无界内存。 */
    @Test
    void limitsScalarLength() {
        rejected(document("<Upload><Key>" + "x".repeat(16_385) + "</Key><UploadId>id</UploadId></Upload>"));
    }

    /** 即使每条很小，供应商超出最大页条数也必须拒绝。 */
    @Test
    void limitsPageEntries() {
        rejected(document("<Upload><Key>key</Key><UploadId>id</UploadId></Upload>".repeat(501)));
    }

    /** 注释等非字段内容仍受原始字节上限，不能仅限制解析后的字段。 */
    @Test
    void limitsRawXmlIncludingComments() {
        rejected(document("<!--" + "x".repeat(2 * 1024 * 1024) + "-->"));
    }

    /** 真正的传输读失败不能被误报为供应商XML身份损坏。 */
    @Test
    void preservesTransportFailureCategory() {
        var broken = new java.io.InputStream() {
            /** 模拟已建立响应后的物理读异常，不依赖任何真实地址。 */
            @Override public int read() throws java.io.IOException {
                throw new java.io.IOException("test transport interrupted");
            }
        };
        assertThatThrownBy(() -> MultipartInventoryParser.parse(broken))
                .isInstanceOfSatisfying(VersionedStorageException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(VersionedStorageException.Reason.UNAVAILABLE));
    }

    /** 含完整根身份的无截断测试页。 */
    private static String document(String entries) {
        return OPEN + "<Bucket>test-bucket</Bucket><IsTruncated>false</IsTruncated>" + entries + CLOSE;
    }

    /** 字节流入口直接覆盖生产安全解析器，避免SDK替身。 */
    private static MultipartInventoryParser.Page parse(String xml) {
        return MultipartInventoryParser.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    /** 固定拒绝原因不能携带未受信任XML正文。 */
    private static void rejected(String xml) {
        assertThatThrownBy(() -> parse(xml)).isInstanceOfSatisfying(VersionedStorageException.class,
                failure -> assertThat(failure.reason()).isEqualTo(VersionedStorageException.Reason.INTEGRITY));
    }
}
