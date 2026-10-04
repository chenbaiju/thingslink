export type Kind = 'DIRECT' | 'GATEWAY' | 'SUB_DEVICE'
export type Protocol =
  | 'STANDARD'
  | 'MODBUS_RTU_PASSTHROUGH'
  | 'MODBUS_TCP_PASSTHROUGH'
  | 'STANDARD_GATEWAY'
  | 'MODBUS_RTU_CLOUD_GATEWAY'
export type Network =
  | 'WIFI'
  | 'ETHERNET'
  | 'CELLULAR_2G'
  | 'CELLULAR_3G'
  | 'CELLULAR_4G'
  | 'CELLULAR_5G'
  | 'NB_IOT'
  | 'BLE'
  | 'ZIGBEE'
  | 'LORA'
  | 'RS485'
  | 'OTHER'
export type PropertyAccessType = 'UNDEFINED' | 'REPORT' | 'DOWNLINK' | 'SHARED' | 'CLOUD_PRIVATE'
export type PropertyDataType = 'NUMBER' | 'TEXT' | 'SWITCH' | 'ENUM'
export type EventLevel = 'INFO' | 'WARNING' | 'ERROR'

export const kindLabels: Record<string, string> = {
  DIRECT: '直连设备',
  GATEWAY: '网关',
  SUB_DEVICE: '网关子设备'
}
export const protocolLabels: Record<string, string> = {
  STANDARD: '标准报文协议',
  MODBUS_RTU_PASSTHROUGH: 'Modbus RTU 透传',
  MODBUS_TCP_PASSTHROUGH: 'Modbus TCP 透传',
  STANDARD_GATEWAY: '标准网关协议',
  MODBUS_RTU_CLOUD_GATEWAY: 'Modbus RTU 云网关'
}
export const networkLabels: Record<string, string> = {
  WIFI: 'WiFi',
  ETHERNET: '以太网',
  CELLULAR_2G: '蜂窝网络 2G',
  CELLULAR_3G: '蜂窝网络 3G',
  CELLULAR_4G: '蜂窝网络 4G',
  CELLULAR_5G: '蜂窝网络 5G',
  NB_IOT: 'NB-IoT',
  BLE: 'BLE',
  ZIGBEE: 'Zigbee',
  LORA: 'LoRa',
  RS485: 'RS485',
  OTHER: '其他'
}
export const accessTypeLabels: Record<string, string> = {
  UNDEFINED: '未定义类型',
  REPORT: '设备上报类型',
  DOWNLINK: '云端下发类型',
  SHARED: '设备云端共享类型',
  CLOUD_PRIVATE: '云端私有类型'
}
export const propertyDataTypeLabels: Record<string, string> = {
  NUMBER: 'Number 数值',
  TEXT: 'Text 文本',
  SWITCH: 'Switch 开关量',
  ENUM: 'Enum 枚举型'
}
export const eventLevelLabels: Record<EventLevel, string> = {
  INFO: '信息',
  WARNING: '警告',
  ERROR: '故障'
}
export const eventLevelTagType: Record<EventLevel, 'info' | 'warning' | 'danger'> = {
  INFO: 'info',
  WARNING: 'warning',
  ERROR: 'danger'
}
const optionsOf = (labels: Record<string, string>) =>
  Object.entries(labels).map(([value, label]) => ({ value, label }))
export const kindOptions = optionsOf(kindLabels)
export const networkOptions = optionsOf(networkLabels)
export const accessTypeOptions = optionsOf(accessTypeLabels)
export const propertyDataTypeOptions = optionsOf(propertyDataTypeLabels)
export const eventLevelOptions = optionsOf(eventLevelLabels)
export const protocolsByKind: Record<Kind, Protocol[]> = {
  DIRECT: ['STANDARD', 'MODBUS_RTU_PASSTHROUGH', 'MODBUS_TCP_PASSTHROUGH'],
  GATEWAY: ['STANDARD_GATEWAY', 'MODBUS_RTU_CLOUD_GATEWAY'],
  SUB_DEVICE: ['STANDARD', 'MODBUS_RTU_PASSTHROUGH']
}
