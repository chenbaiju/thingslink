package com.things.link.device.domain;

/** 人工维护的WGS84当前点；null坐标对表示未设置，版本独立于文本位置。 */
public record DeviceLocationPoint(Double longitude, Double latitude, long version) { }
