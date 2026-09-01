from .protocol import (
    ERR_NOT_LEADER, ERR_NO_QUORUM, ERR_RATE_LIMIT,
    ERR_AUTH, ERR_TXN_CONFLICT, ERR_INVALID_REQ,
)


class AxonSdkError(Exception):
    def __init__(self, message: str, code: int | None = None):
        super().__init__(message)
        self.code = code
        self.name = "AxonSdkError"


class NotLeaderError(AxonSdkError):
    def __init__(self, message: str, leader: str = "", leader_address: str = ""):
        super().__init__(message, ERR_NOT_LEADER)
        self.leader = leader
        self.leader_address = leader_address
        self.name = "NotLeaderError"


class NoQuorumError(AxonSdkError):
    def __init__(self, message: str):
        super().__init__(message, ERR_NO_QUORUM)
        self.name = "NoQuorumError"


class RateLimitError(AxonSdkError):
    def __init__(self, message: str):
        super().__init__(message, ERR_RATE_LIMIT)
        self.name = "RateLimitError"


class ProtocolMismatchError(AxonSdkError):
    def __init__(self, message: str, protocol: int, server_version: str):
        super().__init__(message, ERR_INVALID_REQ)
        self.protocol = protocol
        self.server_version = server_version
        self.name = "ProtocolMismatchError"


class AuthError(AxonSdkError):
    def __init__(self, message: str):
        super().__init__(message, ERR_AUTH)
        self.name = "AuthError"


class ConflictError(AxonSdkError):
    def __init__(self, message: str):
        super().__init__(message, ERR_TXN_CONFLICT)
        self.name = "ConflictError"


def from_rpc_error(err: dict) -> AxonSdkError:
    code = err.get("code")
    msg = err.get("message", "erro desconhecido")
    if code == ERR_NOT_LEADER:
        return NotLeaderError(msg, err.get("leader", ""), err.get("leader_address", ""))
    if code == ERR_NO_QUORUM:
        return NoQuorumError(msg)
    if code == ERR_RATE_LIMIT:
        return RateLimitError(msg)
    if code == ERR_AUTH:
        return AuthError(msg)
    if code == ERR_TXN_CONFLICT:
        return ConflictError(msg)
    if err.get("kind") == "PROTOCOL_MISMATCH":
        return ProtocolMismatchError(msg, err.get("protocol", 0), err.get("version", ""))
    return AxonSdkError(msg, code)