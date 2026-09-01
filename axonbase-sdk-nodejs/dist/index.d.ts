/** AxonBase SDK para Node.js/TypeScript. */
export { Axon } from "./client.js";
export { SagaTransaction } from "./saga.js";
export { SagaParticipantTransaction } from "./saga-participant.js";
export type { AxonOptions, LiveHandler } from "./client.js";
export type { AxonValue, TypedValue } from "./codec.js";
export { encodeTyped, isTypedValue, typedValueToString } from "./codec.js";
export { AxonSdkError, NotLeaderError, NoQuorumError, RateLimitError, ProtocolMismatchError, AuthError, ConflictError, fromRpcError, } from "./errors.js";
export { PROTOCOL_VERSION, METHODS, ERR_PARSE, ERR_INVALID_REQ, ERR_METHOD_NOT_FOUND, ERR_INVALID_PARAMS, ERR_INTERNAL, ERR_AUTH, ERR_RUNTIME, ERR_TXN_CONFLICT, ERR_NOT_LEADER, ERR_NO_QUORUM, ERR_RATE_LIMIT, } from "./protocol.js";
export type { Method, AxonError, HelloFrame, NotificationFrame, KvEntry, Status, CertificateChallenge, CertificateCompletion, } from "./protocol.js";
