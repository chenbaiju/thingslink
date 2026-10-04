package com.things.link.integration.application;
import com.things.link.device.application.*;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

/** 公开设备读取只接受已验证Key主体，不创建Console身份。 */
@Service
@Transactional(readOnly=true,timeout=3)
public class OpenDeviceReadService {
    private final TransactionLocalRlsScope rls;
    private final PublicDeviceReadService devices;
    private final DeviceRuntimeDataService runtime;
    private final RuntimeDeviceQueryParser parser=new RuntimeDeviceQueryParser();
    public OpenDeviceReadService(TransactionLocalRlsScope rls,PublicDeviceReadService devices,DeviceRuntimeDataService runtime){
        this.rls=rls;this.devices=devices;this.runtime=runtime;
    }
    private void establish(ApiKeyPrincipal key){key.require("device:read",false);rls.establish(key.tenantId(),key.projectId());}
    public PublicDeviceReadService.Page catalog(ApiKeyPrincipal key,UUID model,String cursor,int limit){
        establish(key);return devices.catalog(key.projectId(),key.tenantId()+"|"+key.generation()+"|"+key.keyId(),model,cursor,limit);
    }
    public PublicDeviceReadService.Detail detail(ApiKeyPrincipal key,UUID id){establish(key);return devices.detail(key.projectId(),id);}
    public PublicDeviceReadService.Model model(ApiKeyPrincipal key,UUID id){establish(key);return devices.model(key.projectId(),id);}
    public RuntimeDeviceCurrentResult current(ApiKeyPrincipal key,byte[] body){
        establish(key);return runtime.queryCurrentValues(key.projectId(),parser.parseCurrentValues(body));
    }
}
