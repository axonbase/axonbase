namespace AxonBaseSdk;

public static class Protocol
{
    public const int PROTOCOL_VERSION = 1;
    public const string SERVER_VERSION = "0.1.0-SNAPSHOT";
    public const string METHOD_CERTIFICATE_BEGIN = "certificate.begin";
    public const string METHOD_CERTIFICATE_COMPLETE = "certificate.complete";

    public static readonly string[] METHODS =
    [
        "ping", "use", "query", "signin", "authenticate", "live", "kill", "version",
        "begin", "commit", "cancel",
        "kv_get", "kv_set", "kv_del", "kv_scan",
        "let", "set", "unset", "select", "create", "insert", "update", "upsert", "delete", "relate",
        "signup", "invalidate", METHOD_CERTIFICATE_BEGIN, METHOD_CERTIFICATE_COMPLETE,
    ];

    public const int ERR_PARSE = -32700;
    public const int ERR_INVALID_REQ = -32600;
    public const int ERR_METHOD_NOT_FOUND = -32601;
    public const int ERR_INVALID_PARAMS = -32602;
    public const int ERR_INTERNAL = -32000;
    public const int ERR_AUTH = -32002;
    public const int ERR_RUNTIME = -32003;
    public const int ERR_TXN_CONFLICT = -32009;
    public const int ERR_NOT_LEADER = -32010;
    public const int ERR_NO_QUORUM = -32011;
    public const int ERR_RATE_LIMIT = -32029;
}

public record HelloPayload(int Protocol, string Server, string[] Methods);
public record HelloFrame(HelloPayload Hello);

public record NotificationPayload(string Id, string Action, object? Result);
public record NotificationFrame(NotificationPayload Notification);

public record RpcRequest(int Id, string Method, object?[]? Params, int? Version);

public record RpcError(
    int Code,
    string Message,
    string? Kind = null,
    string? Leader = null,
    string? LeaderAddress = null,
    int? Protocol = null,
    string? Version = null
);

public record RpcResponse(int? Id, object? Result = null, RpcError? Error = null);

public record KvEntry(string Key, object? Value, int? Ttl = null);

public record CertificateChallenge(
    string Id,
    string Challenge,
    [property: System.Text.Json.Serialization.JsonPropertyName("expires_at")] string ExpiresAt
);

public record CertificateCompletion(
    string Id,
    string Challenge,
    string Store,
    string User,
    IReadOnlyList<string> Chain,
    string Signature
);

public record Status(
    string NodeId, string ClusterId, string Role,
    string Leader, string LeaderAddress,
    int Term, int CommitIndex,
    int Active, int Members, int Quorum,
    bool Ready
);
