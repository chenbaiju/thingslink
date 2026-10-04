package com.things.link.support.storage;

import io.minio.Http;
import io.minio.ListMultipartUploadsArgs;
import io.minio.MinioAsyncClient;
import java.util.concurrent.CompletableFuture;

/** 复用SDK签名和物理调用范围，只替换有已证实空StorageClass兼容缺陷的盘点解析。 */
final class MultipartInventoryClient extends MinioAsyncClient {
    /** 复制同Scope客户端，不新建连接池或更换签名实现。 */
    MultipartInventoryClient(MinioAsyncClient client) { super(client); }
    /** 发出单页请求并有界解析响应，响应正文在所有路径关闭。 */
    CompletableFuture<MultipartInventoryParser.Page> listInventory(ListMultipartUploadsArgs args) {
        Http.QueryParameters query = new Http.QueryParameters("uploads", "", "delimiter", "",
                "max-uploads", args.maxUploads().toString(), "prefix", args.prefix());
        if (args.keyMarker() != null) { query.put("key-marker", args.keyMarker()); }
        if (args.uploadIdMarker() != null) { query.put("upload-id-marker", args.uploadIdMarker()); }
        return executeGetAsync(args, null, query).thenApply(response -> {
            try (response) { return MultipartInventoryParser.parse(response.body().byteStream()); }
        });
    }
}
