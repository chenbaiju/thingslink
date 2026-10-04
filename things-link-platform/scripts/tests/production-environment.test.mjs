import assert from 'node:assert/strict'
import test from 'node:test'
import { validateProductionEnvironment } from '../production-environment.mjs'

const validEnvironment = {
  SITE_URL: 'https://www.iot-platform.cn',
  PUBLIC_CONSOLE_URL: 'https://console.iot-platform.cn/'
}

test('接受两个独立的正式 HTTPS 地址', () => {
  assert.deepEqual(validateProductionEnvironment(validEnvironment), [])
})

test('允许官网独立部署且未提供控制台地址', () => {
  assert.deepEqual(validateProductionEnvironment({ SITE_URL: 'https://www.chenbaiju.com' }), [])
})

test('报告缺失的官网生产地址', () => {
  const errors = validateProductionEnvironment({})
  assert.equal(errors.length, 1)
  assert.match(errors[0], /SITE_URL/)
})

test('拒绝 HTTP、本机、凭据、查询参数和保留域名', () => {
  const invalidValues = [
    'http://www.iot-platform.cn',
    'https://localhost:4321',
    'https://[::1]',
    'https://user:secret@www.iot-platform.cn',
    'https://www.iot-platform.cn?preview=1',
    'https://platform.example',
    'https://www.iot-platform.cn/subpath'
  ]

  for (const value of invalidValues) {
    const errors = validateProductionEnvironment({
      ...validEnvironment,
      SITE_URL: value
    })
    assert.equal(errors.length, 1, value)
  }
})

test('若配置可选控制台地址，仍拒绝本机地址', () => {
  const errors = validateProductionEnvironment({
    SITE_URL: 'https://www.chenbaiju.com',
    PUBLIC_CONSOLE_URL: 'http://localhost:3006/'
  })
  assert.equal(errors.length, 1)
  assert.match(errors[0], /PUBLIC_CONSOLE_URL/)
})
