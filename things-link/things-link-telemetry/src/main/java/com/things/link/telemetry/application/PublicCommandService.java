package com.things.link.telemetry.application;
import com.things.link.telemetry.domain.*;
import com.things.link.shared.error.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import tools.jackson.databind.*;
import java.time.Instant;
import java.util.*;

/** ADR0173中性命令投影；写入由调用方持项目/账号锁并证明Key控制许可。 */
@Service
@Transactional(propagation=Propagation.MANDATORY)
public class PublicCommandService {
    private final DeviceCommandService commands;private final DeviceCommandRepository repository;private final ObjectMapper json;
    public PublicCommandService(DeviceCommandService commands,DeviceCommandRepository repository,ObjectMapper json){
        this.commands=commands;this.repository=repository;this.json=json.rebuild().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();
    }
    public Result submit(UUID project,UUID device,UUID key,String clientKey,String command,JsonNode input,UUID issuer){
        return result(commands.submitPublicTrusted(project,device,key,clientKey,command,input,issuer));
    }
    /** 仅当前Key来源可查询；无收据时传null，仍统一领域404。 */
    @Transactional(readOnly=true,propagation=Propagation.MANDATORY)
    public Optional<Result> find(UUID project,UUID device,UUID command,UUID key){
        if(command==null||!repository.hasPublicOrigin(project,command,key))return Optional.empty();
        return repository.findById(project,device,command).map(this::result);
    }
    @Transactional(readOnly=true,propagation=Propagation.MANDATORY)
    public Result require(UUID project,UUID device,UUID command,UUID key){
        return find(project,device,command,key).orElseThrow(()->new BusinessException(DeviceCommandErrorCode.COMMAND_NOT_FOUND));
    }
    private Result result(DeviceCommand c){return new Result(c.id(),c.targetDeviceId(),c.commandKey(),c.status().name(),c.attemptCount(),c.maxAttempts(),
        c.acceptedAt(),c.dispatchedAt(),c.acknowledgedAt(),c.completedAt(),c.responseJson()==null?null:json.readTree(c.responseJson()),c.failureCode(),c.failureMessage());}
    @io.swagger.v3.oas.annotations.media.Schema(name="OpenCommandResult")
    public record Result(UUID commandId,UUID deviceId,String commandKey,String status,int attemptCount,int maxAttempts,Instant acceptedAt,
        Instant dispatchedAt,Instant acknowledgedAt,Instant completedAt,
        @io.swagger.v3.oas.annotations.media.Schema(implementation=Object.class,types={"object","array","number","string","boolean","null"}) JsonNode output,
        String failureCode,String failureMessage){}
}
