package axonbase

import "fmt"

type AxonSdkError struct {
	Code    int
	Message string
}

func (e AxonSdkError) Error() string {
	return e.Message
}

type NotLeaderError struct {
	AxonSdkError
	Leader        string
	LeaderAddress string
}

type NoQuorumError struct{ AxonSdkError }

type RateLimitError struct{ AxonSdkError }

type ProtocolMismatchError struct {
	AxonSdkError
	Protocol int
	Version  string
}

type AuthError struct{ AxonSdkError }

type ConflictError struct{ AxonSdkError }

func newNotLeader(msg, leader, addr string) *NotLeaderError {
	return &NotLeaderError{
		AxonSdkError: AxonSdkError{Code: ERR_NOT_LEADER, Message: msg},
		Leader:       leader,
		LeaderAddress: addr,
	}
}

func newNoQuorum(msg string) *NoQuorumError {
	return &NoQuorumError{AxonSdkError: AxonSdkError{Code: ERR_NO_QUORUM, Message: msg}}
}

func newRateLimit(msg string) *RateLimitError {
	return &RateLimitError{AxonSdkError: AxonSdkError{Code: ERR_RATE_LIMIT, Message: msg}}
}

func newAuth(msg string) *AuthError {
	return &AuthError{AxonSdkError: AxonSdkError{Code: ERR_AUTH, Message: msg}}
}

func newConflict(msg string) *ConflictError {
	return &ConflictError{AxonSdkError: AxonSdkError{Code: ERR_TXN_CONFLICT, Message: msg}}
}

func FromRpcError(e *RpcError) error {
	switch e.Code {
	case ERR_NOT_LEADER:
		return newNotLeader(e.Message, e.Leader, e.LeaderAddress)
	case ERR_NO_QUORUM:
		return newNoQuorum(e.Message)
	case ERR_RATE_LIMIT:
		return newRateLimit(e.Message)
	case ERR_AUTH:
		return newAuth(e.Message)
	case ERR_TXN_CONFLICT:
		return newConflict(e.Message)
	default:
		if e.Kind == "PROTOCOL_MISMATCH" {
			return &ProtocolMismatchError{
				AxonSdkError: AxonSdkError{Code: e.Code, Message: e.Message},
				Protocol:     e.Protocol,
				Version:      e.Version,
			}
		}
		return AxonSdkError{Code: e.Code, Message: e.Message}
	}
}

// Ensure NotLeaderError satisfies the error interface.
var _ error = (*NotLeaderError)(nil)

func init() {
	_ = fmt.Sprintf
}