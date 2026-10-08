import {
  expect,
  test,
  type CDPSession,
  type Locator,
  type Page,
  type Request,
  type Response
} from '@playwright/test'
import { createHash } from 'node:crypto'
import {
  guardReviewedRuntime,
  type ReviewedDevice,
  type ReviewedRole,
  type ReviewedSeed
} from './agent-reviewed-runtime'
import { enterProject, login, OWNER_PASSWORD } from './helpers'

test.use({ trace: 'off', video: 'off', screenshot: 'off' })
test.beforeEach(async ({ page }) => {
  test.setTimeout(180_000)
  page.setDefaultTimeout(10_000)
  test.skip(
    !process.env.E2E_AGENT_REVIEWED_RUNTIME || !process.env.E2E_AGENT_REVIEWED_SEED,
    '需独占010-C真实HTTP合成受审夹具；不是生产模型准入'
  )
})
test.afterEach(async ({ page }) => {
  await page.close()
})
const hash = (text: string) => createHash('sha256').update(text).digest('hex')
const stage = (name: string, values: Record<string, number | boolean> = {}) =>
  console.log(JSON.stringify({ stage: '010-C-' + name, syntheticOnly: true, ...values }))
const analysis = (seed: ReviewedSeed) =>
  `/api/v1/projects/${seed.projectA.id}/assistant/analysis-runs`
const capturedJson = new WeakMap<Response, Promise<any>>()
async function bounded<T>(promise: Promise<T>, code: string, milliseconds = 20_000): Promise<T> {
  let timer: ReturnType<typeof setTimeout> | undefined
  try {
    return await Promise.race([
      promise,
      new Promise<T>((_, reject) => {
        timer = setTimeout(() => reject(new Error(code)), milliseconds)
      })
    ])
  } finally {
    if (timer) clearTimeout(timer)
  }
}
function responseFor(page: Page, path: string, method = 'GET') {
  return page.waitForResponse(
    (response) => {
      if (response.request().method() !== method || new URL(response.url()).pathname !== path)
        return false
      const body = response.json()
      void body.catch(() => undefined)
      capturedJson.set(response, body)
      return true
    },
    { timeout: 20_000 }
  )
}
async function json(response: Response) {
  try {
    return await bounded(
      capturedJson.get(response) ?? response.json(),
      'REVIEWED_PUBLIC_RESPONSE_UNREADABLE'
    )
  } catch {
    throw new Error('REVIEWED_PUBLIC_RESPONSE_UNREADABLE')
  }
}
async function select(page: Page, region: Locator, label: string, values: string[]) {
  const input = region.getByRole('combobox', { name: label, exact: true })
  const wrapper = region.locator('.el-select').filter({
    has: page.getByRole('combobox', { name: label, exact: true })
  })
  if ((await input.getAttribute('aria-expanded')) !== 'true')
    await wrapper.click({ timeout: 10_000 })
  for (const value of values) {
    const option = page
      .locator('.el-select-dropdown:visible')
      .getByRole('option', { name: value, exact: true })
    await expect(option).toBeVisible({ timeout: 10_000 })
    await option.click({ timeout: 10_000 })
  }
  await page.keyboard.press('Escape')
}
async function openEvidence(
  page: Page,
  seed: ReviewedSeed,
  device: ReviewedDevice,
  project = seed.projectA
) {
  await page.goto(`/#/device/list?deviceId=${device.id}`)
  await expect(page.locator('.device-detail')).toBeVisible({ timeout: 20_000 })
  await expect(page.locator('.device-detail > .el-loading-mask')).toBeHidden({ timeout: 20_000 })
  await page.getByRole('tab', { name: '诊断证据', exact: true }).click({ timeout: 10_000 })
  const evidence = page.getByTestId('agent-evidence')
  await expect(evidence).toBeVisible({ timeout: 10_000 })
  const reply = responseFor(
    page,
    `/api/v1/projects/${project.id}/devices/${device.id}/binding-metadata`
  )
  await evidence
    .getByRole('button', { name: '加载属性目录', exact: true })
    .click({ timeout: 10_000 })
  const response = await reply
  expect(response.status()).toBe(200)
  const metadata = await json(response)
  expect(
    metadata.devices?.length === 1 &&
      metadata.models?.length === 1 &&
      metadata.devices[0].deviceId === device.id &&
      metadata.devices[0].currentModelVersionId === device.modelVersionId &&
      metadata.models[0].versionId === device.modelVersionId
  ).toBe(true)
  await select(page, evidence, '证据属性', seed.propertyKeys)
  return page.getByTestId('agent-analysis')
}
async function setup(page: Page, baseURL: string, role: ReviewedRole, device?: ReviewedDevice) {
  const seed = await guardReviewedRuntime(baseURL)
  const traffic = observe(page, seed)
  await login(page, seed.actors[role].email, OWNER_PASSWORD)
  await enterProject(page, seed.projectA.name)
  const panel = await openEvidence(page, seed, device ?? seed.deviceA)
  return { seed, panel, traffic }
}
async function consent(panel: Locator) {
  const checkbox = panel.getByRole('checkbox').first()
  await expect(checkbox).toBeEnabled({ timeout: 10_000 })
  await panel
    .locator('.el-checkbox')
    .filter({ hasText: '我确认本次使用项目 Key 分析' })
    .click({ timeout: 10_000 })
  await expect(checkbox).toBeChecked({ timeout: 10_000 })
}
async function available(page: Page, panel: Locator, seed: ReviewedSeed) {
  const reply = responseFor(page, analysis(seed) + '/status')
  await panel.getByRole('button', { name: '查看模型状态', exact: true }).click({ timeout: 10_000 })
  const response = await reply
  expect(response.status()).toBe(200)
  const value = await json(response)
  expect(
    value.businessAvailable === true && value.reason === 'REVIEWED_CONFIGURATION_AVAILABLE'
  ).toBe(true)
  await expect(panel).toContainText('当前条件允许发起分析', { timeout: 10_000 })
  await consent(panel)
}
async function localIntent(page: Page) {
  return page.evaluate(async () => {
    const keys = Object.keys(localStorage).filter((key) =>
      key.startsWith('tc-agent-analysis-intent:v1:')
    )
    if (keys.length !== 1)
      return { count: keys.length, keyHash: '', requestHash: '', sensitiveStored: false }
    const raw = localStorage.getItem(keys[0])!
    const value = JSON.parse(raw)
    const digest = async (text: string) =>
      Array.from(
        new Uint8Array(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(text)))
      )
        .map((byte) => byte.toString(16).padStart(2, '0'))
        .join('')
    const request = value.request
    return {
      count: 1,
      keyHash: await digest(value.key),
      requestHash: await digest(
        JSON.stringify([
          request.deviceId,
          request.expectedModelVersionId,
          request.propertyKeys,
          request.template
        ])
      ),
      sensitiveStored: /"(?:result|summary|usage|credential|apiKey|accessToken|refreshToken)"/.test(
        raw
      )
    }
  })
}
function observe(page: Page, seed: ReviewedSeed) {
  const root = analysis(seed)
  let posts = 0,
    statuses = 0,
    refreshes = 0
  let postKey = '',
    requestHash = ''
  const lookupKeys: string[] = []
  const storedAtDispatch: Array<Promise<Awaited<ReturnType<typeof localIntent>>>> = []
  page.on('request', (request) => {
    const path = new URL(request.url()).pathname
    if (path.endsWith('/auth/refresh')) refreshes++
    if (path === root + '/status') statuses++
    if (path === root + '/by-key' && request.method() === 'GET')
      lookupKeys.push(request.headers()['idempotency-key'] ?? '')
    if (path === root && request.method() === 'POST') {
      posts++
      postKey = request.headers()['idempotency-key'] ?? ''
      const body = JSON.parse(request.postData() ?? '{}')
      requestHash = hash(
        JSON.stringify([
          body.deviceId,
          body.expectedModelVersionId,
          body.propertyKeys,
          body.template
        ])
      )
      const snapshot = localIntent(page)
      void snapshot.catch(() => undefined)
      storedAtDispatch.push(snapshot)
    }
  })
  return {
    counts: () => ({ posts, statuses, refreshes, lookups: lookupKeys.length }),
    sameLookupKey: () =>
      Boolean(postKey) && lookupKeys.length > 0 && lookupKeys.every((key) => key === postKey),
    observeLookups(target: Page) {
      target.on('request', (request) => {
        if (request.method() === 'GET' && new URL(request.url()).pathname === root + '/by-key')
          lookupKeys.push(request.headers()['idempotency-key'] ?? '')
      })
    },
    async intentSavedBeforePost() {
      expect(posts).toBe(1)
      const value = await bounded(
        storedAtDispatch[0],
        'REVIEWED_INTENT_DISPATCH_OBSERVATION_TIMEOUT'
      )
      expect(
        value.count === 1 &&
          value.keyHash === hash(postKey) &&
          value.requestHash === requestHash &&
          !value.sensitiveStored
      ).toBe(true)
    },
    async intentStillOriginal() {
      const value = await localIntent(page)
      expect(
        value.count === 1 &&
          value.keyHash === hash(postKey) &&
          value.requestHash === requestHash &&
          !value.sensitiveStored
      ).toBe(true)
    }
  }
}
async function lookup(page: Page, seed: ReviewedSeed) {
  const panel = page.getByTestId('analysis-recovery')
  const reply = responseFor(page, analysis(seed) + '/by-key')
  await panel
    .getByRole('button', { name: '核对原调用状态', exact: true })
    .click({ timeout: 10_000 })
  const response = await reply
  expect(response.status()).toBe(200)
  return json(response)
}
async function forget(page: Page) {
  const panel = page.getByTestId('analysis-recovery')
  const confirmation = panel.getByRole('checkbox')
  await expect(confirmation).toBeEnabled({ timeout: 10_000 })
  await panel
    .locator('.el-checkbox')
    .filter({ hasText: '我已记录核对结果' })
    .click({ timeout: 10_000 })
  await expect(confirmation).toBeChecked()
  await panel
    .getByRole('button', { name: '确认处置本地意图', exact: true })
    .click({ timeout: 10_000 })
  await expect(panel).toContainText('没有发起新的分析', { timeout: 10_000 })
  expect((await localIntent(page)).count).toBe(0)
}
async function noResurrectedState(panel: Locator) {
  await expect
    .poll(
      () => panel.evaluate((element) => element.textContent?.includes('尚未读取模型状态') === true),
      { timeout: 10_000 }
    )
    .toBe(true)
  await expect(panel.getByRole('button', { name: '确认并分析一次', exact: true })).toBeDisabled()
  await expect(panel.getByRole('checkbox').first()).not.toBeChecked()
  await expect(panel.getByTestId('analysis-result')).toHaveCount(0)
}
async function pausedResponse(page: Page, path: string, method = 'GET') {
  const cdp = await page.context().newCDPSession(page)
  let id = '',
    resolve!: (status: number) => void
  let cancelled = false
  const failed = (request: Request) => {
    if (
      id &&
      request.method() === method &&
      new URL(request.url()).pathname === path &&
      request.failure()?.errorText.includes('ERR_ABORTED')
    )
      cancelled = true
  }
  page.on('requestfailed', failed)
  const observed = new Promise<number>((done) => {
    resolve = done
  })
  const held = bounded(observed, 'REVIEWED_REAL_RESPONSE_HOLD_TIMEOUT')
  void held.catch(() => undefined)
  cdp.on('Fetch.requestPaused', (event) => {
    if (event.request.method === method && new URL(event.request.url).pathname === path) {
      id = event.requestId
      resolve(event.responseStatusCode ?? 0)
    } else void cdp.send('Fetch.continueRequest', { requestId: event.requestId }).catch(() => {})
  })
  await bounded(
    cdp.send('Fetch.enable', {
      patterns: [
        { urlPattern: '*3018' + path, requestStage: 'Response' },
        { urlPattern: '*8089' + path, requestStage: 'Response' }
      ]
    }),
    'REVIEWED_CDP_ENABLE_TIMEOUT'
  )
  return {
    held,
    async release() {
      let released = false
      try {
        await bounded(
          cdp.send('Fetch.continueRequest', { requestId: id }),
          'REVIEWED_CDP_RELEASE_TIMEOUT'
        )
        released = true
      } catch {
        /* 不能继续原请求不等于释放成功；取消仅以实际requestfailed事件确认。 */
      } finally {
        try {
          await cleanup(cdp)
        } finally {
          page.off('requestfailed', failed)
        }
      }
      return { released, cancelled }
    }
  }
}
async function cleanup(cdp: CDPSession) {
  await bounded(cdp.send('Fetch.disable'), 'REVIEWED_CDP_DISABLE_TIMEOUT')
  await bounded(cdp.detach(), 'REVIEWED_CDP_DETACH_TIMEOUT')
}

for (const role of ['OWNER', 'ADMIN', 'OPERATOR'] as const) {
  test(`${role}受审交互仅单次分析，首次纯文本与原键元数据恢复`, async ({ page, baseURL }) => {
    const { seed, panel, traffic } = await setup(page, baseURL!, role)
    await noResurrectedState(panel)
    await select(page, panel, '固定分析问题', ['活动告警说明'])
    expect(traffic.counts().statuses).toBe(0)
    expect(traffic.counts().posts).toBe(0)
    await available(page, panel, seed)
    const fresh = responseFor(page, analysis(seed) + '/status'),
      result = responseFor(page, analysis(seed), 'POST')
    await panel
      .getByRole('button', { name: '确认并分析一次', exact: true })
      .click({ timeout: 10_000 })
    expect((await fresh).status()).toBe(200)
    const response = await result
    expect(response.status()).toBe(200)
    const value = await json(response)
    expect(value.category === 'SUCCEEDED' && value.call?.status === 'SUCCEEDED').toBe(true)
    await traffic.intentSavedBeforePost()
    const output = page.getByTestId('analysis-result')
    await expect
      .poll(
        () =>
          output.evaluate((element, expected) => {
            const text = element.textContent ?? ''
            return (
              text.includes(expected.summary) &&
              text.includes(expected.statement) &&
              text.includes(expected.limitation) &&
              text.includes('输入 ' + expected.usage.promptTokens) &&
              text.includes('输出 ' + expected.usage.completionTokens) &&
              text.includes('合计 ' + expected.usage.totalTokens) &&
              !element.querySelector('script, iframe, img, b')
            )
          }, seed.expectedResult),
        { timeout: 10_000 }
      )
      .toBe(true)
    await expect(panel.getByRole('checkbox').first()).not.toBeChecked()
    await expect(panel.getByRole('button', { name: '确认并分析一次', exact: true })).toBeDisabled()
    await traffic.intentStillOriginal()
    expect(traffic.counts().statuses).toBe(2)
    stage(role + '-FIRST-TEXT', { posts: traffic.counts().posts })
    if (role === 'OPERATOR') {
      // 另一真实标签显式核对/处置，触发原生storage事件清首标签正文，不派发伪事件。
      const other = await page.context().newPage()
      try {
        await other.goto('/#/device/list?deviceId=' + seed.deviceA.id)
        await other.getByRole('tab', { name: '诊断证据', exact: true }).click({ timeout: 10_000 })
        traffic.observeLookups(other)
        const call = await lookup(other, seed)
        expect(
          call.status === 'SUCCEEDED' &&
            !Object.hasOwn(call, 'result') &&
            !Object.hasOwn(call, 'summary')
        ).toBe(true)
        expect(traffic.sameLookupKey()).toBe(true)
        await forget(other)
        await expect(output).toHaveCount(0, { timeout: 10_000 })
        await noResurrectedState(panel)
      } finally {
        await other.close()
      }
      expect(traffic.counts().posts).toBe(1)
      stage('NATIVE-STORAGE-CLEARED-TEXT', { posts: 1 })
    } else {
      await page.reload()
      await page.getByRole('tab', { name: '诊断证据', exact: true }).click({ timeout: 10_000 })
      await expect(page.getByTestId('analysis-result')).toHaveCount(0)
      const call = await lookup(page, seed)
      expect(
        call.status === 'SUCCEEDED' &&
          !Object.hasOwn(call, 'result') &&
          !Object.hasOwn(call, 'summary')
      ).toBe(true)
      expect(traffic.sameLookupKey()).toBe(true)
      await traffic.intentStillOriginal()
      await forget(page)
      expect(traffic.counts().posts).toBe(1)
    }
    stage(role + '-RECOVERED-METADATA', { posts: 1 })
  })
}

test('VIEWER可读证据但没有状态与提交入口，实际状态HTTP拒绝且零POST', async ({ page, baseURL }) => {
  const { seed, panel, traffic } = await setup(page, baseURL!, 'VIEWER')
  await expect(panel).toContainText('查看者可继续读取事实证据')
  await expect(panel.getByRole('button', { name: '查看模型状态', exact: true })).toHaveCount(0)
  await expect(panel.getByRole('button', { name: '确认并分析一次', exact: true })).toHaveCount(0)
  const rejected = responseFor(page, analysis(seed) + '/status')
  await page.evaluate(async (project) => {
    const path = '/src/api/assistant-analysis.ts'
    try {
      await (await import(path)).readAnalysisAvailability(project, new AbortController().signal)
    } catch {
      /* 真实HTTP状态取证，拒绝正文不传播。 */
    }
  }, seed.projectA.id)
  expect((await rejected).status()).toBe(403)
  expect(traffic.counts().posts).toBe(0)
  expect((await localIntent(page)).count).toBe(0)
  stage('VIEWER-DENIED', { posts: 0 })
})

test('配置真实停用后点击重新确权拒绝，零新意图且不自动提交', async ({ page, baseURL }) => {
  const { seed, panel, traffic } = await setup(page, baseURL!, 'OWNER')
  await available(page, panel, seed)
  const change = async (enabled: boolean) =>
    page.evaluate(
      async ({ project, enabled }) => {
        try {
          const path = '/src/api/assistant-model.ts',
            api = await import(path)
          const current = await api.readModelConfiguration(project)
          const updated = await api.setModelEnabled(project, {
            expectedRevision: current.revision,
            enabled
          })
          return updated.enabled === enabled
        } catch {
          throw new Error('REVIEWED_FIXTURE_CONFIGURATION_CHANGE_FAILED')
        }
      },
      { project: seed.projectA.id, enabled }
    )
  try {
    expect(await change(false)).toBe(true)
    const reply = responseFor(page, analysis(seed) + '/status')
    await panel
      .getByRole('button', { name: '确认并分析一次', exact: true })
      .click({ timeout: 10_000 })
    const response = await reply
    expect(response.status()).toBe(200)
    const value = await json(response)
    expect(
      value.businessAvailable === false && value.reason === 'PROJECT_MODEL_CONFIGURATION_DISABLED'
    ).toBe(true)
    await expect(panel).toContainText('当前模型条件已变化，本次未提交分析', { timeout: 10_000 })
    expect(traffic.counts().posts).toBe(0)
    expect((await localIntent(page)).count).toBe(0)
  } finally {
    expect(await change(true)).toBe(true)
  }
  await expect(panel.getByRole('button', { name: '确认并分析一次', exact: true })).toBeDisabled()
  expect(traffic.counts().posts).toBe(0)
  stage('FRESH-ADMISSION-DENIED', { posts: 0 })
})

test('设备、项目与账号切换丢弃真实迟到状态，旧确认不恢复', async ({ page, baseURL }) => {
  const { seed, traffic } = await setup(page, baseURL!, 'OWNER')
  for (const target of ['device', 'project', 'account'] as const) {
    let panel = await openEvidence(page, seed, seed.deviceA)
    const paused = await pausedResponse(page, analysis(seed) + '/status')
    const click = panel
      .getByRole('button', { name: '查看模型状态', exact: true })
      .click({ timeout: 10_000 })
    expect(await paused.held).toBe(200)
    await click
    if (target === 'device') {
      await page.locator('.device-detail__back').click({ timeout: 10_000 })
      await expect(page.getByTestId('agent-analysis')).toHaveCount(0)
      panel = await openEvidence(page, seed, seed.deviceOther)
    }
    if (target === 'project') {
      await enterProject(page, seed.projectB.name)
      panel = await openEvidence(page, seed, seed.deviceB, seed.projectB)
    }
    if (target === 'account') {
      await page.getByAltText('avatar', { exact: true }).first().hover({ timeout: 10_000 })
      await page.locator('.user-menu-popover:visible .log-out').click({ timeout: 10_000 })
      await page
        .locator('.login-out-dialog')
        .getByRole('button', { name: '确定', exact: true })
        .click({ timeout: 10_000 })
      await page.waitForURL((url) => url.hash.includes('/auth/login'), { timeout: 20_000 })
      await login(page, seed.actors.ADMIN.email, OWNER_PASSWORD)
      await enterProject(page, seed.projectA.name)
      panel = await openEvidence(page, seed, seed.deviceA)
    }
    await paused.release()
    await noResurrectedState(panel)
    if (target === 'project') await enterProject(page, seed.projectA.name)
    stage('LATE-' + target.toUpperCase() + '-CLEARED', { posts: traffic.counts().posts })
  }
  expect(traffic.counts().posts).toBe(0)
})

test('真实POST200迟响应关闭后不落新范围，原键只读恢复与明确处置', async ({ page, baseURL }) => {
  const { seed, panel, traffic } = await setup(page, baseURL!, 'OWNER')
  await available(page, panel, seed)
  const paused = await pausedResponse(page, analysis(seed), 'POST')
  let disposed = false
  try {
    const click = panel
      .getByRole('button', { name: '确认并分析一次', exact: true })
      .click({ timeout: 10_000 })
    expect(await paused.held).toBe(200)
    await click
    // 不等待已被scope取消的浏览器response；后续原键GET确认服务器真实成功事实。
    await traffic.intentSavedBeforePost()
    await page.locator('.device-detail__back').click({ timeout: 10_000 })
    await expect(page.getByTestId('agent-analysis')).toHaveCount(0)
    const otherDevice = await openEvidence(page, seed, seed.deviceOther)
    await noResurrectedState(otherDevice)
    await traffic.intentStillOriginal()
    await enterProject(page, seed.projectB.name)
    const otherProject = await openEvidence(page, seed, seed.deviceB, seed.projectB)
    await noResurrectedState(otherProject)
    await traffic.intentStillOriginal()
    const released = await paused.release()
    disposed = true
    expect(released.released || released.cancelled).toBe(true)
    await noResurrectedState(otherProject)
    await traffic.intentStillOriginal()
    expect(traffic.counts().posts).toBe(1)
    stage('LATE-POST-SCOPE-CLEARED', {
      posts: 1,
      responseReleased: released.released,
      responseCancelled: released.cancelled
    })
    await enterProject(page, seed.projectA.name)
    const original = await openEvidence(page, seed, seed.deviceA)
    await noResurrectedState(original)
    const recovered = await lookup(page, seed)
    expect(
      recovered.status === 'SUCCEEDED' &&
        !Object.hasOwn(recovered, 'result') &&
        !Object.hasOwn(recovered, 'summary')
    ).toBe(true)
    expect(traffic.sameLookupKey()).toBe(true)
    await traffic.intentStillOriginal()
    await expect(page.getByTestId('analysis-result')).toHaveCount(0)
    await forget(page)
    expect(traffic.counts().posts).toBe(1)
    stage('LATE-POST-ORIGINAL-METADATA-DISPOSED', { posts: 1, metadataOnly: true })
  } finally {
    if (!disposed) await paused.release()
  }
})

test('实际Security401不认证重放，单次提交后保留原键与原意图', async ({ page, baseURL }) => {
  const { seed, panel, traffic } = await setup(page, baseURL!, 'OWNER')
  await available(page, panel, seed)
  const cdp = await page.context().newCDPSession(page)
  let injected = 0
  cdp.on('Fetch.requestPaused', (event) => {
    if (event.request.method === 'POST' && new URL(event.request.url).pathname === analysis(seed)) {
      injected++
      const headers = Object.entries(event.request.headers)
        .filter(([key]) => key.toLowerCase() !== 'authorization')
        .map(([name, value]) => ({ name, value: String(value) }))
      headers.push({ name: 'Authorization', value: 'Bearer synthetic-invalid-test-only' })
      void cdp
        .send('Fetch.continueRequest', { requestId: event.requestId, headers })
        .catch(() => {})
    } else void cdp.send('Fetch.continueRequest', { requestId: event.requestId }).catch(() => {})
  })
  await bounded(
    cdp.send('Fetch.enable', {
      patterns: [
        { urlPattern: '*3018' + analysis(seed), requestStage: 'Request' },
        { urlPattern: '*8089' + analysis(seed), requestStage: 'Request' }
      ]
    }),
    'REVIEWED_CDP_ENABLE_TIMEOUT'
  )
  const before = traffic.counts().refreshes
  try {
    const reply = responseFor(page, analysis(seed), 'POST')
    await panel
      .getByRole('button', { name: '确认并分析一次', exact: true })
      .click({ timeout: 10_000 })
    expect((await reply).status()).toBe(401)
    await expect(panel).toContainText('分析尚未取得可确认结果', { timeout: 10_000 })
    await traffic.intentSavedBeforePost()
    await traffic.intentStillOriginal()
    expect(injected).toBe(1)
    expect(traffic.counts().posts).toBe(1)
    expect(traffic.counts().refreshes === before).toBe(true)
  } finally {
    await cleanup(cdp)
  }
  // 原键GET真实404也不能清键或发新POST；无效Bearer注入不是撤员资格。
  const original = responseFor(page, analysis(seed) + '/by-key')
  await page
    .getByTestId('analysis-recovery')
    .getByRole('button', { name: '核对原调用状态', exact: true })
    .click({ timeout: 10_000 })
  expect((await original).status()).toBe(404)
  await expect(page.getByTestId('analysis-recovery')).toContainText('未找到记录不代表未消费')
  expect(traffic.sameLookupKey()).toBe(true)
  await traffic.intentStillOriginal()
  expect(traffic.counts().posts).toBe(1)
  stage('INVALID-BEARER-401-NO-REPLAY', { posts: 1, automaticRefresh: false })
})

test('合成内部Transport未知只原键GET恢复，不重新POST或自动处置', async ({ page, baseURL }) => {
  const seed = await guardReviewedRuntime(baseURL!)
  const traffic = observe(page, seed)
  await login(page, seed.actors.OWNER.email, OWNER_PASSWORD)
  await enterProject(page, seed.projectA.name)
  let panel = await openEvidence(page, seed, seed.unknownDevice)
  await available(page, panel, seed)
  const reply = responseFor(page, analysis(seed), 'POST')
  await panel
    .getByRole('button', { name: '确认并分析一次', exact: true })
    .click({ timeout: 10_000 })
  const response = await reply
  expect(response.status()).toBe(200)
  const value = await json(response)
  expect(
    value.category === 'TRANSPORT_UNKNOWN' &&
      value.call?.status === 'UNKNOWN' &&
      value.result === null
  ).toBe(true)
  await traffic.intentSavedBeforePost()
  await expect(page.getByTestId('analysis-result')).toContainText('传输结果未知', {
    timeout: 10_000
  })
  await page.reload()
  await page.getByRole('tab', { name: '诊断证据', exact: true }).click({ timeout: 10_000 })
  panel = page.getByTestId('agent-analysis')
  await expect(page.getByTestId('analysis-result')).toHaveCount(0)
  const recovered = await lookup(page, seed)
  expect(recovered.status === 'UNKNOWN' && !Object.hasOwn(recovered, 'result')).toBe(true)
  expect(traffic.sameLookupKey()).toBe(true)
  await traffic.intentStillOriginal()
  await expect(panel).toContainText('不能推断零费用')
  expect(traffic.counts().posts).toBe(1)
  stage('UNKNOWN-GET-ONLY', { posts: 1, lookupOnly: true })
})
