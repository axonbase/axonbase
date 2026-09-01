namespace AxonBaseSdk;

public class AxonSdkException : Exception
{
    public int? Code { get; }

    public AxonSdkException(string message, int? code = null) : base(message)
    {
        Code = code;
    }
}

public class NotLeaderException : AxonSdkException
{
    public string Leader { get; }
    public string LeaderAddress { get; }

    public NotLeaderException(string message, string leader, string leaderAddress)
        : base(message, Protocol.ERR_NOT_LEADER)
    {
        Leader = leader;
        LeaderAddress = leaderAddress;
    }
}

public class NoQuorumException : AxonSdkException
{
    public NoQuorumException(string message) : base(message, Protocol.ERR_NO_QUORUM) { }
}

public class RateLimitException : AxonSdkException
{
    public RateLimitException(string message) : base(message, Protocol.ERR_RATE_LIMIT) { }
}

public class AuthException : AxonSdkException
{
    public AuthException(string message) : base(message, Protocol.ERR_AUTH) { }
}

public class ConflictException : AxonSdkException
{
    public ConflictException(string message) : base(message, Protocol.ERR_TXN_CONFLICT) { }
}

public class ProtocolMismatchException : AxonSdkException
{
    public int ServerProtocol { get; }
    public string ServerVersion { get; }

    public ProtocolMismatchException(string message, int protocol, string version)
        : base(message, Protocol.ERR_INVALID_REQ)
    {
        ServerProtocol = protocol;
        ServerVersion = version;
    }
}

public static class ErrorFactory
{
    public static AxonSdkException FromRpcError(RpcError err)
    {
        return err.Code switch
        {
            Protocol.ERR_NOT_LEADER => new NotLeaderException(err.Message, err.Leader ?? "", err.LeaderAddress ?? ""),
            Protocol.ERR_NO_QUORUM => new NoQuorumException(err.Message),
            Protocol.ERR_RATE_LIMIT => new RateLimitException(err.Message),
            Protocol.ERR_AUTH => new AuthException(err.Message),
            Protocol.ERR_TXN_CONFLICT => new ConflictException(err.Message),
            _ when err.Kind == "PROTOCOL_MISMATCH" => new ProtocolMismatchException(err.Message, err.Protocol ?? 0, err.Version ?? ""),
            _ => new AxonSdkException(err.Message, err.Code),
        };
    }
}