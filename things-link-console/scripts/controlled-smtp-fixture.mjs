#!/usr/bin/env node
/** Local TLS+AUTH SMTP receiver. Never relays mail. Receipts contain hashes, no address/body. */
import { createServer } from 'node:tls'
import { createHash, randomBytes, timingSafeEqual } from 'node:crypto'
import { readFileSync, writeFileSync, renameSync, chmodSync, lstatSync, existsSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import path from 'node:path'

const digest = (value) => createHash('sha256').update(value).digest('hex')
const encoded = (value) => Buffer.from(value).toString('base64')
const equal = (a, b) =>
  Buffer.byteLength(a) === Buffer.byteLength(b) && timingSafeEqual(Buffer.from(a), Buffer.from(b))
const save = (file, value) => {
  writeFileSync(file + '.pending', JSON.stringify(value), { mode: 0o600 })
  chmodSync(file + '.pending', 0o600)
  renameSync(file + '.pending', file)
}

/** Explicit identity-only opt-in: the default receiver never stores message contents. */
export function validateIdentityCapture(directory, capture) {
  if (capture === undefined) return undefined
  if (
    capture?.selector !== 'invitation-registration-smtp.spec.ts' ||
    capture?.purpose !== 'private-identity-registration' ||
    !/^invited-[a-f0-9]{32}@example\.test$/.test(capture?.recipient ?? '') ||
    Object.keys(capture).sort().join(',') !== 'purpose,recipient,selector'
  )
    throw new Error('Invalid private identity capture opt-in')
  const stat = lstatSync(directory)
  if (
    !stat.isDirectory() ||
    stat.isSymbolicLink() ||
    (stat.mode & 0o077) !== 0 ||
    stat.uid !== process.getuid()
  )
    throw new Error('Private identity capture requires an owned private directory')
  const file = path.join(directory, 'private-identity-mail.json')
  if (existsSync(file) || existsSync(file + '.pending'))
    throw new Error('Refusing existing private identity capture')
  return { recipient: capture.recipient, file }
}

export async function startControlledSmtp({ cert, key, directory, identityCapture }) {
  const privateCapture = validateIdentityCapture(directory, identityCapture)
  const privateMessages = []
  if (privateCapture) save(privateCapture.file, privateMessages)
  const username = 'fixture-' + randomBytes(24).toString('hex') + '@example.test'
  const password = randomBytes(32).toString('hex')
  const receipts = []
  const attempts = new Map()
  const sockets = new Set()
  const receiptFile = path.join(directory, 'smtp-receipts.json')
  save(receiptFile, receipts)
  const server = createServer(
    { cert: readFileSync(cert), key: readFileSync(key), minVersion: 'TLSv1.2' },
    (socket) => {
      sockets.add(socket)
      socket.setTimeout(10000, () => socket.destroy())
      socket.on('error', () => socket.destroy())
      socket.on('close', () => sockets.delete(socket))
      const reply = (line) => socket.write(line + '\r\n')
      let buffer = '',
        state = 'COMMAND',
        user = '',
        authenticated = false,
        recipient = '',
        data = ''
      let commands = 0
      reply('220 localhost controlled receiver')
      socket.on('data', (chunk) => {
        buffer += chunk.toString('ascii')
        if (buffer.length > 65536) return socket.destroy()
        let end
        while ((end = buffer.indexOf('\r\n')) >= 0) {
          const line = buffer.slice(0, end)
          buffer = buffer.slice(end + 2)
          if (line.length > 8192) return socket.destroy()
          if (state === 'USER') {
            user = line
            state = 'PASSWORD'
            reply('334 UGFzc3dvcmQ6')
            continue
          }
          if (state === 'PASSWORD') {
            authenticated = equal(user, encoded(username)) && equal(line, encoded(password))
            state = 'COMMAND'
            reply(authenticated ? '235 2.7.0 Authenticated' : '535 5.7.8 Rejected')
            continue
          }
          if (state === 'DATA') {
            if (line !== '.') {
              data += (line.startsWith('..') ? line.slice(1) : line) + '\r\n'
              if (data.length > 65536) return socket.destroy()
              continue
            }
            const attempt = (attempts.get(recipient) ?? 0) + 1
            attempts.set(recipient, attempt)
            const accepted = Boolean(privateCapture) || attempt > 1
            let mime
            try {
              mime = privateCapture ? parseIdentityMime(data) : parseMime(data)
            } catch {
              state = 'COMMAND'
              data = ''
              recipient = ''
              reply('554 Unsupported controlled MIME')
              continue
            }
            if (privateCapture) {
              if (privateMessages.length >= 4) return socket.destroy()
              privateMessages.push({ targetSha256: digest(recipient), ...mime })
              save(privateCapture.file, privateMessages)
            }
            receipts.push({
              targetSha256: digest(recipient),
              subjectSha256: digest(mime.subject),
              bodySha256: digest(mime.body),
              attempt,
              accepted,
              receivedAt: new Date().toISOString(),
              tls: socket.getProtocol(),
              authenticated: true
            })
            if (receipts.length > 100) return socket.destroy()
            save(receiptFile, receipts)
            state = 'COMMAND'
            data = ''
            recipient = ''
            reply(
              accepted ? '250 2.0.0 Accepted by controlled receiver' : '451 4.3.0 Controlled retry'
            )
            continue
          }
          if (++commands > 100) return socket.destroy()
          if (line.startsWith('EHLO ')) reply('250-localhost\r\n250 AUTH LOGIN')
          else if (line === 'AUTH LOGIN') {
            state = 'USER'
            reply('334 VXNlcm5hbWU6')
          } else if (line.startsWith('MAIL FROM:') && authenticated) reply('250 Sender accepted')
          else if (line.startsWith('RCPT TO:') && authenticated) {
            const match = /^RCPT TO:<([^<>\s]+@example\.test)>$/.exec(line)
            if (!match || (privateCapture && match[1] !== privateCapture.recipient)) {
              reply('550 Only fictional local recipients allowed')
              continue
            }
            recipient = match[1]
            reply('250 Recipient accepted')
          } else if (line === 'DATA' && authenticated && recipient) {
            state = 'DATA'
            reply('354 End with dot')
          } else if (line === 'RSET') {
            state = 'COMMAND'
            recipient = ''
            data = ''
            reply('250 Reset')
          } else if (line === 'QUIT') {
            reply('221 Bye')
            socket.end()
            return
          } else {
            reply('500 Unsupported fixture command')
            socket.end()
            return
          }
        }
      })
    }
  )
  server.maxConnections = 8
  server.on('tlsClientError', () => {}) // Failed trust/auth probes contain no message data.
  await new Promise((resolve, reject) => {
    server.once('error', reject)
    server.listen(0, '127.0.0.1', resolve)
  })
  const config = { host: '127.0.0.1', port: server.address().port, username, password, receiptFile }
  save(path.join(directory, 'smtp-config.json'), config)
  return {
    config,
    close: () =>
      new Promise((resolve) => {
        for (const socket of sockets) socket.destroy()
        server.close(resolve)
      })
  }
}

/** Only the production plain-text MIME contract, bounded before entry; rejects unsupported encoding. */
export function parseIdentityMime(raw) {
  let count = 0
  const plain = []
  const visit = (part, depth) => {
    if (++count > 12 || depth > 4 || Buffer.byteLength(part) > 65536)
      throw new Error('Identity MIME exceeds bounded contract')
    const split = part.indexOf('\r\n\r\n')
    if (split < 0) throw new Error('Invalid identity MIME')
    const headers = part.slice(0, split).replace(/\r\n[ \t]+/g, ' ')
    const type = /^Content-Type:\s*(.+)$/im.exec(headers)?.[1] ?? ''
    if (/^text\/plain;/i.test(type)) {
      plain.push(parseMime(part).body)
    } else if (/^text\/html;/i.test(type)) {
      // The production alternative's HTML is never rendered or persisted.
    } else if (/^multipart\/(?:mixed|related|alternative);/i.test(type)) {
      const boundary = /;\s*boundary=(?:"([^"\r\n]{1,100})"|([^;\s]{1,100}))/i.exec(type)
      const value = boundary?.[1] ?? boundary?.[2]
      if (!value) throw new Error('Missing identity MIME boundary')
      const body = part.slice(split + 4)
      const blocks = body.split('--' + value)
      if (blocks.length < 3 || !blocks.at(-1).startsWith('--'))
        throw new Error('Incomplete identity MIME boundary')
      for (const child of blocks.slice(1, -1)) {
        if (!child.startsWith('\r\n') || !child.endsWith('\r\n'))
          throw new Error('Invalid identity MIME part')
        visit(child.slice(2, -2), depth + 1)
      }
    } else throw new Error('Unsupported identity MIME content type')
  }
  visit(raw, 0)
  if (plain.length !== 1)
    throw new Error('Identity MIME requires exactly one plain-text alternative')
  const headers = raw.slice(0, raw.indexOf('\r\n\r\n')).replace(/\r\n[ \t]+/g, ' ')
  const subject = /^Subject:\s*(.*)$/im.exec(headers)?.[1] ?? ''
  return { subject: decodeSubject(subject), body: plain[0] }
}

const decodeSubject = (subject) =>
  subject
    // JavaMail may fold in the middle of a word. RFC 2047 separator whitespace
    // between adjacent encoded words is not part of the decoded subject.
    .replace(/(=\?UTF-8\?[BQ]\?[^?]*\?=)[ \t]+(?==\?UTF-8\?[BQ]\?)/gi, '$1')
    .replace(/=\?UTF-8\?([BQ])\?([^?]*)\?=/gi, (_, encoding, value) =>
      encoding.toUpperCase() === 'B'
        ? Buffer.from(value, 'base64').toString('utf8')
        : decodeQuoted(value.replace(/_/g, ' '))
    )
const decodeQuoted = (value) =>
  Buffer.from(
    value
      .replace(/=\r\n/g, '')
      .replace(/=([a-f\d]{2})/gi, (_, hex) => String.fromCharCode(Number.parseInt(hex, 16))),
    'latin1'
  ).toString('utf8')

export function parseMime(raw) {
  const split = raw.indexOf('\r\n\r\n')
  if (split < 0) throw new Error('Invalid controlled SMTP MIME')
  const header = raw.slice(0, split).replace(/\r\n[ \t]+/g, ' ')
  const fields = new Map(
    header.split('\r\n').map((line) => {
      const index = line.indexOf(':')
      return [line.slice(0, index).toLowerCase(), line.slice(index + 1).trim()]
    })
  )
  const subject = decodeSubject(fields.get('subject') ?? '')
  const contentType = fields.get('content-type') ?? ''
  if (!/^text\/plain;.*charset=(?:"?utf-8"?)/i.test(contentType))
    throw new Error('Unsupported controlled SMTP MIME content type')
  const encoding = (fields.get('content-transfer-encoding') ?? '7bit').toLowerCase()
  const content = raw.slice(split + 4)
  const body =
    encoding === 'base64'
      ? Buffer.from(content, 'base64').toString('utf8')
      : encoding === 'quoted-printable'
        ? decodeQuoted(content)
        : encoding === '7bit'
          ? content
          : null
  if (body === null) throw new Error('Unsupported controlled SMTP MIME encoding')
  return { subject, body: body.replace(/\r?\n/g, '\n').trimEnd() }
}

if (process.argv[1] && fileURLToPath(import.meta.url) === path.resolve(process.argv[1])) {
  process.umask(0o077)
  const directory = process.argv[2]
  if (!directory || process.argv.length !== 3)
    throw new Error('Usage: controlled-smtp-fixture.mjs PRIVATE_TLS_DIRECTORY')
  const mode = process.env.E2E_SMTP_PRIVATE_IDENTITY
  let identityCapture
  if (mode !== undefined) {
    if (
      mode !== '1' ||
      !existsSync('/.thingslink-local-ci') ||
      process.env.E2E_SPEC !== 'invitation-registration-smtp.spec.ts'
    )
      throw new Error('Private identity receiver requires exact isolated selection')
    identityCapture = {
      selector: process.env.E2E_SPEC,
      purpose: 'private-identity-registration',
      recipient: process.env.E2E_INVITATION_RECIPIENT
    }
  } else if (process.env.E2E_INVITATION_RECIPIENT !== undefined) {
    throw new Error('Identity recipient without private opt-in')
  }
  const receiver = await startControlledSmtp({
    directory,
    identityCapture,
    cert: path.join(directory, 'server.pem'),
    key: path.join(directory, 'server.key')
  })
  const stop = async () => {
    await receiver.close()
    process.exit(0)
  }
  process.once('SIGTERM', stop)
  process.once('SIGINT', stop)
  process.stdout.write('Controlled TLS SMTP receiver ready (local receipt only)\n')
}
