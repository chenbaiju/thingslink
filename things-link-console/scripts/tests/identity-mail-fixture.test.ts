import test from 'node:test'
import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { mkdtempSync, writeFileSync, chmodSync, rmSync, symlinkSync, unlinkSync } from 'node:fs'
import { tmpdir } from 'node:os'
import path from 'node:path'
import { parseIdentityLink, readIdentityLink } from '../../e2e/identity-mail-fixture'

const recipient = 'invited-' + 'a'.repeat(32) + '@example.test'
const id = '019927ba-5678-7000-8000-123456789abc'
const origin = 'http://localhost:3006'
const secret = 'b'.repeat(43)
const invitation = `${origin}/#/auth/project-invitation/${id}?code=${secret}`
const verification = `${origin}/#/auth/verify-email?token=${secret}`
const mail = (body = invitation, subject = '项目协作邀请') => ({
  targetSha256: createHash('sha256').update(recipient).digest('hex'),
  subject,
  body
})

test('actual private mail parser separates invitation and verification and waits only for absent kind', () => {
  assert.equal(parseIdentityLink([], recipient, origin, 'invitation', id), undefined)
  assert.deepEqual(parseIdentityLink([mail()], recipient, origin, 'invitation', id), {
    fragment: new URL(invitation).hash,
    secret
  })
  assert.equal(parseIdentityLink([mail()], recipient, origin, 'verification', id), undefined)
  assert.deepEqual(
    parseIdentityLink(
      [mail(), mail(verification, '验证你的 ThingsLink 邮箱')],
      recipient,
      origin,
      'verification',
      id
    ),
    { fragment: new URL(verification).hash, secret }
  )
})

test('private proof parser rejects recipient/project/origin rebinding, query secrets and ambiguous mail without echoing data', () => {
  const cases = [
    [mail(invitation.replace('localhost', 'evil.example'))],
    [mail(invitation.replace('http://', 'http://user:password@'))],
    [mail(invitation.replace('/#/', '/?leak=' + secret + '#/'))],
    [mail(invitation.replace(id, '019927ba-5678-7000-8000-123456789abd'))],
    [mail(invitation + '&extra=1')],
    [mail(), mail()],
    [mail(invitation + '\n' + verification)],
    [{ ...mail(), targetSha256: 'c'.repeat(64) }],
    [{ ...mail(), rawToken: secret }],
    [mail('x'.repeat(65537))],
    [mail(invitation.replace('?code=', '?token='))]
  ]
  for (const input of cases)
    assert.throws(
      () => parseIdentityLink(input, recipient, origin, 'invitation', id),
      (error: Error) =>
        error.message === 'Private identity mail contract rejected' &&
        !error.message.includes(secret)
    )
})

test('private mailbox reader requires owned 0700 directory and 0600 regular file; malformed data is redacted', () => {
  const directory = mkdtempSync(path.join(tmpdir(), 'identity-mail-'))
  const file = path.join(directory, 'private-identity-mail.json')
  try {
    writeFileSync(file, JSON.stringify([mail()]), { mode: 0o600 })
    assert.equal(readIdentityLink(directory, recipient, origin, 'invitation', id)?.secret, secret)
    chmodSync(file, 0o644)
    assert.throws(() => readIdentityLink(directory, recipient, origin, 'invitation', id))
    chmodSync(file, 0o600)
    chmodSync(directory, 0o755)
    assert.throws(() => readIdentityLink(directory, recipient, origin, 'invitation', id))
    chmodSync(directory, 0o700)
    writeFileSync(file, secret)
    assert.throws(
      () => readIdentityLink(directory, recipient, origin, 'invitation', id),
      /contract rejected/
    )
    unlinkSync(file)
    symlinkSync('outside', file)
    assert.throws(() => readIdentityLink(directory, recipient, origin, 'invitation', id))
  } finally {
    rmSync(directory, { recursive: true, force: true })
  }
})
