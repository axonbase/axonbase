import { Axon } from "./client.js";
/** Client-side helper for an AxonBase distributed saga. */
export declare class SagaTransaction {
    private readonly axon;
    readonly sagaName: string;
    readonly correlationId: string;
    private begun;
    private finished;
    constructor(axon: Axon, sagaName: string, correlationId: string);
    get isBegun(): boolean;
    get isFinished(): boolean;
    begin(): Promise<void>;
    step(axonql: string): Promise<unknown>;
    commit(): Promise<void>;
    rollback(): Promise<void>;
    describe(): Promise<unknown>;
    private assertActive;
}
