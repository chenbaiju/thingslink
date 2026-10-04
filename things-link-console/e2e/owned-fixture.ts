/** Incremental cleanup ownership. Errors carry labels, never credentials or API request bodies. */
export class OwnedFixture {
  private actions: { label: string; run: () => Promise<unknown> }[] = []

  own(label: string, run: () => Promise<unknown>) {
    this.actions.push({ label, run })
  }

  async finish(primary?: { error: unknown }) {
    const failures: Error[] = []
    for (const action of this.actions.splice(0).reverse()) {
      try {
        await action.run()
      } catch {
        failures.push(new Error(`Owned fixture cleanup failed: ${action.label}`))
      }
    }
    if (failures.length) {
      throw new AggregateError(
        primary ? [primary.error, ...failures] : failures,
        'Controlled fixture failed; original result and cleanup failures are retained separately'
      )
    }
    if (primary) throw primary.error
  }
}
