"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.SagaTransaction = void 0;
const errors_js_1 = require("./errors.js");
/** Client-side helper for an AxonBase distributed saga. */
class SagaTransaction {
    axon;
    sagaName;
    correlationId;
    begun = false;
    finished = false;
    constructor(axon, sagaName, correlationId) {
        this.axon = axon;
        this.sagaName = sagaName;
        this.correlationId = correlationId;
    }
    get isBegun() { return this.begun; }
    get isFinished() { return this.finished; }
    async begin() {
        if (this.begun)
            throw new errors_js_1.AxonSdkError(`Saga already begun: ${this.correlationId}`);
        const result = await this.axon.query(`BEGIN SAGA ${escapeSagaValue(this.sagaName)} WITH CORRELATION '${escapeSagaValue(this.correlationId)}'`);
        if (hasStatus(result, "RUNNING")) {
            this.begun = true;
            return;
        }
        throw new errors_js_1.AxonSdkError(`Failed to begin saga: ${JSON.stringify(result)}`);
    }
    async step(axonql) {
        this.assertActive();
        await this.axon.letVar("saga_corr", this.correlationId);
        return this.axon.query(axonql);
    }
    async commit() {
        this.assertActive();
        const result = await this.axon.query(`COMMIT SAGA ${escapeSagaValue(this.sagaName)} WITH CORRELATION '${escapeSagaValue(this.correlationId)}'`);
        this.finished = true;
        if (!hasStatus(result, "COMMITTED")) {
            throw new errors_js_1.AxonSdkError(`Saga commit failed: ${JSON.stringify(result)}`);
        }
    }
    async rollback() {
        if (!this.begun || this.finished)
            return;
        await this.axon.query(`CANCEL SAGA ${escapeSagaValue(this.sagaName)} WITH CORRELATION '${escapeSagaValue(this.correlationId)}'`);
        this.finished = true;
    }
    describe() {
        return this.axon.query(`SHOW SAGA TRANSACTION ${escapeSagaValue(this.sagaName)} '${escapeSagaValue(this.correlationId)}'`);
    }
    assertActive() {
        if (!this.begun)
            throw new errors_js_1.AxonSdkError("Saga not begun");
        if (this.finished)
            throw new errors_js_1.AxonSdkError("Saga already finished");
    }
}
exports.SagaTransaction = SagaTransaction;
function hasStatus(result, expected) {
    return typeof result === "object" && result !== null && "status" in result
        && result.status === expected;
}
function escapeSagaValue(value) {
    return value.replace(/\\/g, "\\\\").replace(/'/g, "''");
}
