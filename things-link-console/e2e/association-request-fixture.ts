export type AssociationReply<T> =
  | { ok: true; value: T }
  | { ok: false; code: unknown; status: unknown }

export interface AssociationFailure {
  operation: string
  code: number | null
  status: number | null
}

/** Pace only this bulk-data fixture. A rejected write is never retried or converted to success. */
export class AssociationRequester {
  private tail: Promise<unknown> = Promise.resolve()
  failure: AssociationFailure | null = null

  constructor(
    private readonly wait: (milliseconds: number) => Promise<void> = (milliseconds) =>
      new Promise((resolve) => setTimeout(resolve, milliseconds))
  ) {}

  request<T>(operation: string, perform: () => Promise<AssociationReply<T>>): Promise<T> {
    const next = this.tail
      .catch(() => undefined)
      .then(async () => {
        await this.wait(250)
        const reply = await perform()
        if (reply.ok) return reply.value
        this.failure = {
          operation,
          code: this.integer(reply.code, 1, 99999),
          status: this.integer(reply.status, 100, 599)
        }
        throw new Error(
          `Controlled association API failed: ${operation}; code=${this.failure.code}; status=${this.failure.status}`
        )
      })
    this.tail = next
    return next
  }

  private integer(value: unknown, minimum: number, maximum: number): number | null {
    return typeof value === 'number' &&
      Number.isSafeInteger(value) &&
      value >= minimum &&
      value <= maximum
      ? value
      : null
  }
}
