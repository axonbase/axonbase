"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.SagaParticipantTransaction = void 0;
const errors_js_1 = require("./errors.js");
/** Joins an active Saga while controlling only this client's local transaction. */
class SagaParticipantTransaction {
    axon;
    correlationId;
    begun = false;
    finished = false;
    constructor(axon, correlationId) {
        this.axon = axon;
        this.correlationId = correlationId;
    }
    get isBegun() { return this.begun; }
    get isFinished() { return this.finished; }
    async begin() {
        if (this.begun)
            throw new errors_js_1.AxonSdkError(`Transaction already begun: ${this.correlationId}`);
        await this.axon.begin();
        try {
            await this.axon.letVar("saga_corr", this.correlationId);
            this.begun = true;
        }
        catch (error) {
            try {
                await this.axon.cancel();
            }
            catch { /* preserve the setup error */ }
            throw error;
        }
    }
    async step(axonql) {
        this.assertActive();
        return this.axon.query(axonql);
    }
    async commit() {
        this.assertActive();
        await this.axon.commit();
        this.finished = true;
    }
    async rollback() {
        if (!this.begun || this.finished)
            return;
        await this.axon.cancel();
        this.finished = true;
    }
    assertActive() {
        if (!this.begun)
            throw new errors_js_1.AxonSdkError("Transaction not begun");
        if (this.finished)
            throw new errors_js_1.AxonSdkError("Transaction already finished");
    }
}
exports.SagaParticipantTransaction = SagaParticipantTransaction;
