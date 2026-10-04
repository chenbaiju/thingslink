package com.things.link.bootstrap.rule.fixture;

import com.things.link.ThingsLinkApplication;
import com.things.link.rule.application.automation.AutomationExecutionRunner;
import com.things.link.rule.domain.AutomationExecutionRepository;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.nio.file.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** 两进程故障入口仅位于测试classpath；全部业务SQL、受理及恢复仍是生产Bean。 */
public final class AutomationNodeProcess {
    private static Path directory;
    private static final AtomicBoolean arm=new AtomicBoolean();
    private static volatile CountDownLatch barrier=new CountDownLatch(0);
    private static volatile boolean scanning;
    private static volatile boolean timeScanning;
    private static final AtomicBoolean armSchedule=new AtomicBoolean();
    private AutomationNodeProcess() {}
    public static void main(String[] args)throws Exception{
        System.setProperty("socksProxyHost", "");System.setProperty("http.proxyHost", "");
        directory=Path.of(System.getProperty("automation.fixture.directory"));
        try(var context=new SpringApplicationBuilder(ThingsLinkApplication.class,Faults.class).profiles("test").run(args);
            var pool=Executors.newVirtualThreadPerTaskExecutor();
            var input=new BufferedReader(new InputStreamReader(System.in,java.nio.charset.StandardCharsets.UTF_8))){
            context.getBean(KafkaListenerEndpointRegistry.class).getListenerContainers().stream()
                    .filter(c->"things-link-rule-notification".equals(c.getGroupId())).forEach(c->c.start());
            System.out.println("AUTOMATION_FIXTURE publisher="+context.containsBean("kafkaTransactionalOutboxPublisher")+" enabled="+context.getEnvironment().getProperty("things-link.outbox.publisher.enabled")+" tasks="+context.getBean(org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks().size());
            write("ready",Long.toString(ProcessHandle.current().pid()));
            for(String line;(line=input.readLine())!=null;){
                var parts=line.split(" ");String operation=parts[0];if(operation.equals("quit"))break;
                String id=parts[1];
                if(operation.equals("arm")){Files.deleteIfExists(directory.resolve("claimed"));barrier=new CountDownLatch(1);arm.set(true);}
                else if(operation.equals("arm-schedule")){Files.deleteIfExists(directory.resolve("schedule-claimed"));barrier=new CountDownLatch(1);armSchedule.set(true);}
                else if(operation.equals("time-scan-on"))timeScanning=true;
                else if(operation.equals("time-scan-off"))timeScanning=false;
                else if(operation.equals("run-time")){
                    var candidate=new com.things.link.rule.domain.AutomationScheduleRepository.Candidate(UUID.fromString(parts[2]),UUID.fromString(parts[3]),UUID.fromString(parts[4]));
                    pool.submit(()->{try{context.getBean(com.things.link.rule.application.automation.AutomationTimeScheduler.class).run(candidate);write("done-"+id,"ok");}
                        catch(Exception failure){try{write("done-"+id,"FAIL:"+failure.getClass().getSimpleName());}catch(Exception ignored){throw new IllegalStateException(ignored);}}});continue;
                }
                else if(operation.equals("release"))barrier.countDown();
                else if(operation.equals("scan-on"))scanning=true;
                else if(operation.equals("scan-off"))scanning=false;
                else if(operation.equals("run")){
                    var candidate=new AutomationExecutionRepository.Candidate(UUID.fromString(parts[2]),UUID.fromString(parts[3]),UUID.fromString(parts[4]),UUID.fromString(parts[5]));
                    pool.submit(()->{try{context.getBean(AutomationExecutionRunner.class).run(candidate);write("done-"+id,"ok");}
                        catch(Exception failure){try{write("done-"+id,"FAIL:"+failure.getClass().getSimpleName());}catch(Exception ignored){throw new IllegalStateException(ignored);}}});
                    continue;
                }
                write("done-"+id,"ok");
            }
            scanning=false;timeScanning=false;barrier.countDown();
        }
    }
    private static synchronized void write(String name,String value)throws Exception{
        Path target=directory.resolve(name),pending=directory.resolve(name+".tmp");
        Files.writeString(pending,value);Files.move(pending,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
    }
    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods=false)
    public static class Faults {
        @org.springframework.context.annotation.Bean
        static org.springframework.beans.factory.config.BeanPostProcessor hooks(){
            return new org.springframework.beans.factory.config.BeanPostProcessor(){
                @Override public Object postProcessAfterInitialization(Object bean,String name){
                    String type=org.springframework.aop.support.AopUtils.getTargetClass(bean).getSimpleName();
                    if(!Set.of("AutomationExecutionScheduler","JdbcAutomationExecutionRepository","RuleNotificationDeliveryService","NotificationWorkCoordinator","AutomationTimeScheduler","JdbcAutomationScheduleRepository").contains(type))return bean;
                    org.aopalliance.intercept.MethodInterceptor hook=invocation->{
                        String method=invocation.getMethod().getName();
                        if(type.equals("AutomationTimeScheduler")&&method.equals("scan")&&!timeScanning)return null;
                        if(type.equals("AutomationExecutionScheduler")&&method.equals("scan")&&!scanning)return null;
                        // 本资格只验证持久投递接管，不调用真实SMTP或外部HTTP。
                        if(type.equals("NotificationWorkCoordinator")&&method.equals("dispatchReadyWork"))return null;
                        Object result=invocation.proceed();
                        if(type.equals("JdbcAutomationExecutionRepository")&&method.equals("claim")&&arm.compareAndSet(true,false)){
                            if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("claim needs real transaction");
                            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
                                @Override public void afterCommit(){try{
                                    write("claimed",Long.toString(ProcessHandle.current().pid()));
                                    if(!barrier.await(120,TimeUnit.SECONDS))throw new IllegalStateException("test barrier timed out");
                                }catch(Exception failure){throw new IllegalStateException(failure);}}
                            });
                        }
                        if(type.equals("JdbcAutomationScheduleRepository")&&method.equals("claim")&&result instanceof java.util.Optional<?> claimed&&claimed.isPresent()&&armSchedule.compareAndSet(true,false)){
                            if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("schedule claim needs real transaction");
                            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
                                @Override public void afterCommit(){try{write("schedule-claimed",Long.toString(ProcessHandle.current().pid()));
                                    if(!barrier.await(120,TimeUnit.SECONDS))throw new IllegalStateException("schedule test barrier timed out");
                                }catch(Exception failure){throw new IllegalStateException(failure);}}
                            });
                        }
                        if(type.equals("RuleNotificationDeliveryService")&&method.equals("accept")){
                            var request=(com.things.link.shared.message.RuleNotificationDeliveryRequest)invocation.getArguments()[0];
                            if(request.automationExecutionId()!=null)write("notification-"+request.eventId(),request.automationExecutionId().toString());
                        }
                        return result;
                    };
                    if(bean instanceof org.springframework.aop.framework.Advised advised){advised.addAdvice(hook);return bean;}
                    var proxy=new org.springframework.aop.framework.ProxyFactory(bean);proxy.setProxyTargetClass(true);proxy.addAdvice(hook);return proxy.getProxy();
                }
            };
        }
    }
}
