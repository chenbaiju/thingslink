import { createHash } from 'node:crypto'
import { lstatSync, readFileSync } from 'node:fs'
import path from 'node:path'

export type IdentityMailKind = 'invitation' | 'verification'
const hash = (value: string) => createHash('sha256').update(value).digest('hex')
const deny = (): never => {
  throw new Error('Private identity mail contract rejected')
}
const uuid = '[a-f0-9]{8}-[a-f0-9]{4}-7[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}'

/** Pure parser returns secrets only to the caller; no arbitrary URL may escape the loopback origin. */
export function parseIdentityLink(
  raw: unknown,
  recipient: string,
  origin: string,
  kind: IdentityMailKind,
  invitationId: string
) {
  if (
    !/^invited-[a-f0-9]{32}@example\.test$/.test(recipient) ||
    !new RegExp(`^${uuid}$`).test(invitationId)
  )
    deny()
  let base: URL
  try {
    base = new URL(origin)
  } catch {
    return deny()
  }
  if (
    base.protocol !== 'http:' ||
    !['localhost', '127.0.0.1'].includes(base.hostname) ||
    base.origin !== origin ||
    !base.port ||
    base.username ||
    base.password
  )
    deny()
  if (!Array.isArray(raw) || raw.length > 4) return deny()
  const subject = kind === 'invitation' ? '项目协作邀请' : '验证你的 ThingsLink 邮箱'
  const matches: { body: string }[] = []
  for (const value of raw) {
    if (
      !value ||
      typeof value !== 'object' ||
      Object.keys(value).sort().join(',') !== 'body,subject,targetSha256' ||
      value.targetSha256 !== hash(recipient) ||
      typeof value.subject !== 'string' ||
      typeof value.body !== 'string' ||
      Buffer.byteLength(value.body) > 65536
    )
      deny()
    if (value.subject === subject) matches.push(value)
    else if (!['项目协作邀请', '验证你的 ThingsLink 邮箱'].includes(value.subject)) deny()
  }
  if (matches.length === 0) return undefined
  if (matches.length !== 1) deny()
  const urls = matches[0]!.body
    .split('\n')
    .map((line) => line.trim())
    .filter((line) => /^https?:\/\//.test(line))
  if (urls.length !== 1) deny()
  let link: URL
  try {
    link = new URL(urls[0]!)
  } catch {
    return deny()
  }
  if (
    link.origin !== origin ||
    link.username ||
    link.password ||
    link.pathname !== '/' ||
    link.search
  )
    deny()
  const expected =
    kind === 'invitation'
      ? `^#/auth/project-invitation/${invitationId}\\?code=([A-Za-z0-9_-]{43})$`
      : '^#/auth/verify-email\\?token=([A-Za-z0-9_-]{43})$'
  const proof = new RegExp(expected).exec(link.hash)
  if (!proof) return deny()
  return { fragment: link.hash, secret: proof[1]! }
}

/** SMTP writes atomically. Refuse public/symlink files and never include content in parse errors. */
export function readIdentityLink(
  directory: string,
  recipient: string,
  origin: string,
  kind: IdentityMailKind,
  invitationId: string
) {
  try {
    const uid = process.getuid?.()
    if (uid === undefined) return deny()
    const dir = lstatSync(directory)
    const file = path.join(directory, 'private-identity-mail.json')
    const stat = lstatSync(file)
    if (
      !dir.isDirectory() ||
      dir.isSymbolicLink() ||
      (dir.mode & 0o077) !== 0 ||
      dir.uid !== uid ||
      !stat.isFile() ||
      stat.isSymbolicLink() ||
      (stat.mode & 0o077) !== 0 ||
      stat.uid !== uid ||
      stat.size > 262144
    )
      deny()
    return parseIdentityLink(
      JSON.parse(readFileSync(file, 'utf8')),
      recipient,
      origin,
      kind,
      invitationId
    )
  } catch {
    return deny()
  }
}
