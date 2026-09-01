package axonbase

const PROTOCOL_VERSION = 1
const SERVER_VERSION = "0.1.0-SNAPSHOT"

var METHODS = []string{
	"ping", "use", "query", "signin", "authenticate", "live", "kill", "version",
	"begin", "commit", "cancel",
	"kv_get", "kv_set", "kv_del", "kv_scan",
	"let", "set", "unset", "select", "create", "insert", "update", "upsert", "delete", "relate",
	"signup", "invalidate", "certificate.begin", "certificate.complete",
}

const (
	ERR_PARSE            = -32700
	ERR_INVALID_REQ      = -32600
	ERR_METHOD_NOT_FOUND = -32601
	ERR_INVALID_PARAMS   = -32602
	ERR_INTERNAL         = -32000
	ERR_AUTH             = -32002
	ERR_RUNTIME          = -32003
	ERR_TXN_CONFLICT     = -32009
	ERR_NOT_LEADER       = -32010
	ERR_NO_QUORUM        = -32011
	ERR_RATE_LIMIT       = -32029
)

type HelloFrame struct {
	Hello HelloPayload `json:"hello"`
}

type HelloPayload struct {
	Protocol int      `json:"protocol"`
	Server   string   `json:"server"`
	Methods  []string `json:"methods"`
}

type NotificationFrame struct {
	Notification NotificationPayload `json:"notification"`
}

type NotificationPayload struct {
	ID     string      `json:"id"`
	Action string      `json:"action"`
	Result interface{} `json:"result"`
}

type RpcRequest struct {
	ID      int           `json:"id"`
	Method  string        `json:"method"`
	Params  []interface{} `json:"params"`
	Version int           `json:"version,omitempty"`
}

type RpcResponse struct {
	ID     int         `json:"id"`
	Result interface{} `json:"result,omitempty"`
	Error  *RpcError   `json:"error,omitempty"`
}

type RpcError struct {
	Code          int    `json:"code"`
	Message       string `json:"message"`
	Kind          string `json:"kind,omitempty"`
	Leader        string `json:"leader,omitempty"`
	LeaderAddress string `json:"leader_address,omitempty"`
	Protocol      int    `json:"protocol,omitempty"`
	Version       string `json:"version,omitempty"`
}

type KvEntry struct {
	Key   string      `json:"key"`
	Value interface{} `json:"value"`
	TTL   *int        `json:"ttl,omitempty"`
}

type CertificateChallenge struct {
	ID        string `json:"id"`
	Challenge string `json:"challenge"`
	ExpiresAt string `json:"expires_at"`
}

type CertificateCompletion struct {
	ID        string   `json:"id"`
	Challenge string   `json:"challenge"`
	Store     string   `json:"store"`
	User      string   `json:"user"`
	Chain     []string `json:"chain"`
	Signature string   `json:"signature"`
}

type Status struct {
	NodeID      string `json:"node_id"`
	ClusterID   string `json:"cluster_id"`
	Role        string `json:"role"`
	Leader      string `json:"leader"`
	LeaderAddr  string `json:"leader_address"`
	Term        int    `json:"term"`
	CommitIndex int    `json:"commit_index"`
	Active      int    `json:"active"`
	Members     int    `json:"members"`
	Quorum      int    `json:"quorum"`
	Ready       bool   `json:"ready"`
}
