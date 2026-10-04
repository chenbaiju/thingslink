package com.things.link.device.application;
import com.things.link.device.domain.*;
import com.things.link.shared.error.*;
import com.things.link.support.query.SignedQueryCursorCodec;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;

/** ADR0173所属域中性投影；调用方必须在同事务建立Key权威双轴RLS。 */
@Service
@Transactional(propagation=Propagation.MANDATORY, readOnly=true)
public class PublicDeviceReadService {
    private final PublicDeviceCatalogRepository catalog;
    private final DeviceRepository devices;
    private final ThingModelVersionRepository models;
    private final DeviceRuntimeDataService runtime;
    private final SignedQueryCursorCodec cursors;
    private final ObjectMapper json;
    /** 所有仓储仅在device模块内使用，跨域结果无domain类型。 */
    public PublicDeviceReadService(PublicDeviceCatalogRepository catalog, DeviceRepository devices,
            ThingModelVersionRepository models, DeviceRuntimeDataService runtime, SignedQueryCursorCodec cursors, ObjectMapper json) {
        this.catalog=catalog;this.devices=devices;this.models=models;this.runtime=runtime;this.cursors=cursors;this.json=json.rebuild().enable(tools.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();
    }
    /** 当前身份/过滤绑定的只读分页；身份字符串由受信integration编排构造。 */
    public Page catalog(UUID project, String identity, UUID model, String cursor, int limit) {
        if(limit<1||limit>50)throw new BusinessException(CommonErrorCode.INVALID_PARAMETER);
        String binding=identity+"|"+project+"|"+model+"|"+limit+"|createdAt,id:DESC";
        var anchor=cursors.decode(cursor,"OPEN_DEVICE_CATALOG",binding);
        var rows=catalog.findPublic(project,model,anchor.map(SignedQueryCursorCodec.Anchor::sortTime).orElse(null),
                anchor.map(SignedQueryCursorCodec.Anchor::sortId).orElse(null),limit+1);
        if(rows.size()>limit+1)throw new IllegalStateException("目录仓储越过行预算");
        boolean more=rows.size()>limit;
        var visible=rows.stream().limit(limit).map(r->new Item(r.deviceId(),r.name(),r.deviceStatus(),r.currentModelVersionId(),r.createdAt())).toList();
        String next=more?cursors.encode("OPEN_DEVICE_CATALOG",binding,visible.getLast().createdAt(),visible.getLast().deviceId()):null;
        return new Page(visible,next,more);
    }
    /** 未绑定设备保留null模型，不返回凭据与内部路由。 */
    public Detail detail(UUID project,UUID id){
        var d=devices.findById(project,id).orElseThrow(()->new BusinessException(DeviceErrorCode.DEVICE_NOT_FOUND));
        UUID model=models.findCurrent(project,id).map(ThingModelVersionRepository.ResolvedBinding::currentVersionId).orElse(null);
        return new Detail(d.id(),d.name(),d.status().name(),model,d.createdAt(),d.lastOnlineAt());
    }
    /** 不可变完整版本，不用只有属性的运行模型投影替代commands/events。 */
    public Model model(UUID project,UUID id){
        var m=models.findPublished(project,id).orElseThrow(()->new BusinessException(DeviceErrorCode.THING_MODEL_VERSION_NOT_FOUND));
        return new Model(m.id(),m.deviceTypeId(),m.versionNumber(),m.digestAlgorithm(),m.schemaDigest(),m.publishedAt(),json.readTree(m.modelSnapshot()));
    }
    @io.swagger.v3.oas.annotations.media.Schema(name="OpenDeviceItem")
    public record Item(UUID deviceId,String name,String deviceStatus,UUID currentModelVersionId,Instant createdAt){}
    @io.swagger.v3.oas.annotations.media.Schema(name="OpenDeviceDetail")
    public record Detail(UUID deviceId,String name,String deviceStatus,UUID currentModelVersionId,Instant createdAt,Instant lastOnlineAt){}
    @io.swagger.v3.oas.annotations.media.Schema(name="OpenDevicePage")
    public record Page(List<Item> items,String nextCursor,boolean hasMore){public Page{items=List.copyOf(items);}}
    @io.swagger.v3.oas.annotations.media.Schema(name="OpenDeviceModel")
    public record Model(UUID versionId,UUID deviceTypeId,String versionNumber,String digestAlgorithm,String digest,Instant publishedAt,@io.swagger.v3.oas.annotations.media.Schema(implementation=Object.class,type="object",additionalProperties=io.swagger.v3.oas.annotations.media.Schema.AdditionalPropertiesValue.TRUE) JsonNode snapshot){}
}
