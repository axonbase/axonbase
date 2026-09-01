"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.ConflictError = exports.AuthError = exports.ProtocolMismatchError = exports.RateLimitError = exports.NoQuorumError = exports.NotLeaderError = exports.AxonSdkError = void 0;
exports.fromRpcError = fromRpcError;
/** Erros específicos do SDK Axon. */
class AxonSdkError extends Error {
    code;
    constructor(message, code) {
        super(message);
        this.code = code;
        this.name = "AxonSdkError";
    }
}
exports.AxonSdkError = AxonSdkError;
class NotLeaderError extends AxonSdkError {
    leader;
    leaderAddress;
    constructor(message, leader, leaderAddress) {
        super(message, -32010);
        this.leader = leader;
        this.leaderAddress = leaderAddress;
        this.name = "NotLeaderError";
    }
}
exports.NotLeaderError = NotLeaderError;
class NoQuorumError extends AxonSdkError {
    constructor(message) {
        super(message, -32011);
        this.name = "NoQuorumError";
    }
}
exports.NoQuorumError = NoQuorumError;
class RateLimitError extends AxonSdkError {
    constructor(message) {
        super(message, -32029);
        this.name = "RateLimitError";
    }
}
exports.RateLimitError = RateLimitError;
class ProtocolMismatchError extends AxonSdkError {
    protocol;
    serverVersion;
    constructor(message, protocol, serverVersion) {
        super(message, -32600);
        this.protocol = protocol;
        this.serverVersion = serverVersion;
        this.name = "ProtocolMismatchError";
    }
}
exports.ProtocolMismatchError = ProtocolMismatchError;
class AuthError extends AxonSdkError {
    constructor(message) {
        super(message, -32002);
        this.name = "AuthError";
    }
}
exports.AuthError = AuthError;
class ConflictError extends AxonSdkError {
    constructor(message) {
        super(message, -32009);
        this.name = "ConflictError";
    }
}
exports.ConflictError = ConflictError;
function fromRpcError(err) {
    switch (err.code) {
        case -32010: return new NotLeaderError(err.message, err.leader ?? "", err.leader_address ?? "");
        case -32011: return new NoQuorumError(err.message);
        case -32029: return new RateLimitError(err.message);
        case -32002: return new AuthError(err.message);
        case -32009: return new ConflictError(err.message);
        default: return new AxonSdkError(err.message, err.code);
    }
}
