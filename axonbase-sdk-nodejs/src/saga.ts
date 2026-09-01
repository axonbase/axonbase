import { Axon } from "./client.js";
import { AxonSdkError } from "./errors.js";

/** Client-side helper for an AxonBase distributed saga. */
export class SagaTransaction {
  private begun = false;
  private finished = false;

  constructor(
    private readonly axon: Axon,
    readonly sagaName: string,
    readonly correlationId: string,
  ) {}

  get isBegun(): boolean { return this.begun; }
  get isFinished(): boolean { return this.finished; }

  async begin(): Promise<void> {
    if (this.begun) throw new AxonSdkError(`Saga already begun: ${this.correlationId}`);
    const result = await this.axon.query(
      `BEGIN SAGA ${escapeSagaValue(this.sagaName)} WITH CORRELATION '${escapeSagaValue(this.correlationId)}'`,
    );
    if (hasStatus(result, "RUNNING")) {
      this.begun = true;
      return;
    }
    throw new AxonSdkError(`Failed to begin saga: ${JSON.stringify(result)}`);
  }

  async step(axonql: string): Promise<unknown> {
    this.assertActive();
    await this.axon.letVar("saga_corr", this.correlationId);
    return this.axon.query(axonql);
  }

  async commit(): Promise<void> {
    this.assertActive();
    const result = await this.axon.query(
      `COMMIT SAGA ${escapeSagaValue(this.sagaName)} WITH CORRELATION '${escapeSagaValue(this.correlationId)}'`,
    );
    this.finished = true;
    if (!hasStatus(result, "COMMITTED")) {
      throw new AxonSdkError(`Saga commit failed: ${JSON.stringify(result)}`);
    }
  }

  async rollback(): Promise<void> {
    if (!this.begun || this.finished) return;
    await this.axon.query(
      `CANCEL SAGA ${escapeSagaValue(this.sagaName)} WITH CORRELATION '${escapeSagaValue(this.correlationId)}'`,
    );
    this.finished = true;
  }

  describe(): Promise<unknown> {
    return this.axon.query(
      `SHOW SAGA TRANSACTION ${escapeSagaValue(this.sagaName)} '${escapeSagaValue(this.correlationId)}'`,
    );
  }

  private assertActive(): void {
    if (!this.begun) throw new AxonSdkError("Saga not begun");
    if (this.finished) throw new AxonSdkError("Saga already finished");
  }
}

function hasStatus(result: unknown, expected: string): boolean {
  return typeof result === "object" && result !== null && "status" in result
    && (result as { status?: unknown }).status === expected;
}

function escapeSagaValue(value: string): string {
  return value.replace(/\\/g, "\\\\").replace(/'/g, "''");
}
