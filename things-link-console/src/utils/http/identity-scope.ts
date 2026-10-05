import { CanceledError } from 'axios'
import { shallowRef } from 'vue'

/** ADR0094：仅当前标签的内存身份代次，不持久化令牌或跨标签推断身份。 */
const identityEpoch = shallowRef(0)

/** 请求调用时冻结身份；正常令牌轮换不改变它。 */
export function currentIdentityEpoch(): number {
  return identityEpoch.value
}

/** 登录、退出或账号/项目改变使旧请求失效，即使后来切回同一账号也不能复用。 */
export function invalidateIdentity(): void {
  identityEpoch.value++
}

/** 旧身份以Axios取消语义安静拒绝；不能据此声称撤销了已送达HTTP或Set-Cookie。 */
export function assertCurrentIdentity(epoch: number): void {
  if (epoch !== identityEpoch.value) throw new CanceledError('请求所属身份已改变')
}
