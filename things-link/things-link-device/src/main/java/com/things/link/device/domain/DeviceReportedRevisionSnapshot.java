package com.things.link.device.domain;
import java.util.UUID;
/** 普通RLS读取的有界序号元数据，不携带属性大值。
 * @param deviceId 设备身份 @param reportedRevisions 属性接受序号JSON对象 */
public record DeviceReportedRevisionSnapshot(UUID deviceId, String reportedRevisions) { }
