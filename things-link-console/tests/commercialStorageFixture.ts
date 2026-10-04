/** Node26全局localStorage未配置持久文件；测试显式提供标准Storage端口，不接触宿主数据。 */
export function memoryStorage(): Storage {
  const values = new Map<string, string>()
  return {
    get length() {
      return values.size
    },
    clear() {
      values.clear()
    },
    getItem(key) {
      return values.get(key) ?? null
    },
    setItem(key, value) {
      values.set(key, String(value))
    },
    removeItem(key) {
      values.delete(key)
    },
    key(index) {
      return [...values.keys()][index] ?? null
    }
  }
}
