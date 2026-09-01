import { Axon } from "./client.js";
import { AxonSdkError } from "./errors.js";

/** Joins an active Saga while controlling only this client's local transaction. */
export class SagaParticipantTransaction {
  private begun = false;
  private finished = false;

  constructor(
    private readonly axon: Axon,
    readonly correlationId: string,
  ) {}

  get isBegun(): boolean { return this.begun; }
  get isFinished(): boolean { return this.finished; }

  async begin(): Promise<void> {
    if (this.begun) throw new AxonSdkError(`Transaction already begun: ${this.correlationId}`);
    await this.axon.begin();
    try {
      await this.axon.letVar("saga_corr", this.correlationId);
      this.begun = true;
    } catch (error) {
      try { await this.axon.cancel(); } catch { /* preserve the setup error */ }
      throw error;
    }
  }

  async step(axonql: string): Promise<unknown> {
    this.assertActive();
    return this.axon.query(axonql);
  }

  async commit(): Promise<void> {
    this.assertActive();
    await this.axon.commit();
    this.finished = true;
  }

  async rollback(): Promise<void> {
    if (!this.begun || this.finished) return;
    await this.axon.cancel();
    this.finished = true;
  }

  private assertActive(): void {
    if (!this.begun) throw new AxonSdkError("Transaction not begun");
    if (this.finished) throw new AxonSdkError("Transaction already finished");
  }
}
