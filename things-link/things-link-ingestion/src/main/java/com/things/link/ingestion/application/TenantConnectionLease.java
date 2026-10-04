package com.things.link.ingestion.application;
/** 兼容原实时装配；中性租户共享租约端口由project.application持有。 */
public interface TenantConnectionLease extends com.things.link.project.application.RealtimeConnectionLease {}
