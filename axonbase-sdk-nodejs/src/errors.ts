/** Erros específicos do SDK Axon. */
export class AxonSdkError extends Error {
  constructor(message: string, public readonly code?: number) {
    super(message);
    this.name = "AxonSdkError";
  }
}

export class NotLeaderError extends AxonSdkError {
  constructor(
    message: string,
    public readonly leader: string,
    public readonly leaderAddress: string,
  ) {
    super(message, -32010);
    this.name = "NotLeaderError";
  }
}

export class NoQuorumError extends AxonSdkError {
  constructor(message: string) {
    super(message, -32011);
    this.name = "NoQuorumError";
  }
}

export class RateLimitError extends AxonSdkError {
  constructor(message: string) {
    super(message, -32029);
    this.name = "RateLimitError";
  }
}

export class ProtocolMismatchError extends AxonSdkError {
  constructor(message: string, public readonly protocol: number, public readonly serverVersion: string) {
    super(message, -32600);
    this.name = "ProtocolMismatchError";
  }
}

export class AuthError extends AxonSdkError {
  constructor(message: string) {
    super(message, -32002);
    this.name = "AuthError";
  }
}

export class ConflictError extends AxonSdkError {
  constructor(message: string) {
    super(message, -32009);
    this.name = "ConflictError";
  }
}

export function fromRpcError(err: AxonError): AxonSdkError {
  switch (err.code) {
    case -32010: return new NotLeaderError(err.message, err.leader ?? "", err.leader_address ?? "");
    case -32011: return new NoQuorumError(err.message);
    case -32029: return new RateLimitError(err.message);
    case -32002: return new AuthError(err.message);
    case -32009: return new ConflictError(err.message);
    default: return new AxonSdkError(err.message, err.code);
  }
}

import { AxonError } from "./protocol.js";