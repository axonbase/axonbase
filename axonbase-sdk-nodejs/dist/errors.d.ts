/** Erros específicos do SDK Axon. */
export declare class AxonSdkError extends Error {
    readonly code?: number | undefined;
    constructor(message: string, code?: number | undefined);
}
export declare class NotLeaderError extends AxonSdkError {
    readonly leader: string;
    readonly leaderAddress: string;
    constructor(message: string, leader: string, leaderAddress: string);
}
export declare class NoQuorumError extends AxonSdkError {
    constructor(message: string);
}
export declare class RateLimitError extends AxonSdkError {
    constructor(message: string);
}
export declare class ProtocolMismatchError extends AxonSdkError {
    readonly protocol: number;
    readonly serverVersion: string;
    constructor(message: string, protocol: number, serverVersion: string);
}
export declare class AuthError extends AxonSdkError {
    constructor(message: string);
}
export declare class ConflictError extends AxonSdkError {
    constructor(message: string);
}
export declare function fromRpcError(err: AxonError): AxonSdkError;
import { AxonError } from "./protocol.js";
