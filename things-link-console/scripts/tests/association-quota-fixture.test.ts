import { test } from 'node:test'
import assert from 'node:assert/strict'
import { assertAssociationFixture } from '../../e2e/association-quota-fixture'

const env = {
  E2E_SPEC: 'device-associations-populated.spec.ts',
  E2E_ASSOCIATION_FIXTURE: '1',
  E2E_OWNER_EMAIL: 'e2e-owner-test@example.com',
  E2E_AUTOMATION_ADMIN_USER: 'owned',
  E2E_AUTOMATION_ADMIN_DB: 'owned'
}
test('only exact isolated association selection admits controlled policy setup', () => {
  assert.doesNotThrow(() => assertAssociationFixture(env, true))
  assert.throws(() => assertAssociationFixture(env, false))
  for (const field of Object.keys(env)) {
    assert.throws(() => assertAssociationFixture({ ...env, [field]: '' }, true))
  }
  for (const value of ['device-associations.spec.ts', '*', 'property-automation-smtp.spec.ts']) {
    assert.throws(() => assertAssociationFixture({ ...env, E2E_SPEC: value }, true))
  }
  assert.throws(() => assertAssociationFixture({ ...env, E2E_OWNER_EMAIL: "x'@example.com" }, true))
})
