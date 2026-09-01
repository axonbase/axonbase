import { Axon } from "./client.js";
/** Joins an active Saga while controlling only this client's local transaction. */
export declare class SagaParticipantTransaction {
    private readonly axon;
    readonly correlationId: string;
    private begun;
    private finished;
    constructor(axon: Axon, correlationId: string);
    get isBegun(): boolean;
    get isFinished(): boolean;
    begin(): Promise<void>;
    step(axonql: string): Promise<unknown>;
    commit(): Promise<void>;
    rollback(): Promise<void>;
    private assertActive;
}
