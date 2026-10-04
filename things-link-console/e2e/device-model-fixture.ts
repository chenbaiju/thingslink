import { execFile } from 'node:child_process'
import { promisify } from 'node:util'

/** 仅测试夹具读取真实绑定事实；不直接造库、不用类型整数版本猜设备语义版本。 */
export async function readBoundModelVersion(projectId: string, deviceId: string): Promise<string> {
  const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
  const user = process.env.E2E_PG_USER,
    database = process.env.E2E_PG_DB
  if (!uuid.test(projectId) || !uuid.test(deviceId) || !user || !database)
    throw new Error('缺少真实绑定版本读取上下文')
  try {
    const { stdout } = await promisify(execFile)(
      'docker',
      [
        'exec',
        '-e',
        'PGOPTIONS=-c statement_timeout=3000',
        'tc-postgres',
        'psql',
        '-qAt',
        '-U',
        user,
        '-d',
        database,
        '-c',
        `SELECT version.version_number FROM dev_device device JOIN dev_thing_model_version version ON version.id=device.thing_model_version_id WHERE device.id='${deviceId}'::uuid AND device.project_id='${projectId}'::uuid AND version.project_id=device.project_id`
      ],
      { timeout: 5000, maxBuffer: 4096 }
    )
    const version = stdout.trim()
    if (!/^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$/.test(version))
      throw new Error('invalid version')
    return version
  } catch {
    throw new Error('真实设备绑定版本读取失败，未发送遥测')
  }
}
