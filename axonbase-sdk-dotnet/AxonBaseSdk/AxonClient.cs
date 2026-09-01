using System.Net.WebSockets;
using System.Net.Security;
using System.Security.Cryptography.X509Certificates;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace AxonBaseSdk;

public delegate void LiveHandler(string id, string action, object? result);

public class AxonClient : IDisposable
{
    private string _url;
    private readonly TimeSpan _timeout;
    private readonly bool _reconnect;
    private readonly TimeSpan _reconnectInterval;
    private readonly int _maxReconnectAttempts;
    private readonly X509CertificateCollection _clientCertificates;
    private readonly X509Certificate2Collection _trustedServerRoots;

    private ClientWebSocket? _ws;
    private int _nextId = 1;
    private readonly Dictionary<int, TaskCompletionSource<RpcResponse>> _pending = new();
    private readonly Dictionary<string, LiveHandler> _liveHandlers = new();
    private bool _handshakeReceived;
    private string _namespace = "";
    private string _database = "";
    private string? _auth;
    private HelloPayload? _hello;
    private bool _closed;
    private CancellationTokenSource? _readCts;

    private static readonly JsonSerializerOptions JsonOpts = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull,
    };

    public AxonClient(string url, AxonClientOptions? opts = null)
    {
        _url = url;
        opts ??= new AxonClientOptions();
        _timeout = opts.Timeout;
        _reconnect = opts.Reconnect;
        _reconnectInterval = opts.ReconnectInterval;
        _maxReconnectAttempts = opts.MaxReconnectAttempts;
        _clientCertificates = opts.ClientCertificates;
        _trustedServerRoots = opts.TrustedServerRoots;
    }

    public static async Task<AxonClient> ConnectAsync(string url, AxonClientOptions? opts = null)
    {
        var client = new AxonClient(url, opts);
        await client.OpenAsync();
        return client;
    }

    public async Task OpenAsync()
    {
        _ws?.Dispose();
        _ws = CreateWebSocket();
        _readCts?.Cancel();
        _readCts = new CancellationTokenSource();

        await _ws.ConnectAsync(new Uri(_url), _readCts.Token);

        var buffer = new byte[1024 * 64];
        var result = await _ws.ReceiveAsync(new ArraySegment<byte>(buffer), _readCts.Token);
        var helloText = Encoding.UTF8.GetString(buffer, 0, result.Count);

        var hello = JsonSerializer.Deserialize<HelloFrame>(helloText, JsonOpts)
            ?? throw new AxonSdkException("handshake hello esperado como primeiro frame");
        _hello = hello.Hello;
        _handshakeReceived = true;

        _ = ReadLoopAsync(_readCts.Token);
    }

    internal ClientWebSocket CreateWebSocket()
    {
        var webSocket = new ClientWebSocket();
        foreach (var certificate in _clientCertificates)
            webSocket.Options.ClientCertificates.Add(certificate);
        if (_trustedServerRoots.Count > 0)
            webSocket.Options.RemoteCertificateValidationCallback = ValidateServerCertificate;
        return webSocket;
    }

    private bool ValidateServerCertificate(object? _, X509Certificate? certificate, X509Chain? chain, SslPolicyErrors sslPolicyErrors)
    {
        if (certificate is null || (sslPolicyErrors & SslPolicyErrors.RemoteCertificateNameMismatch) != 0)
            return false;

        using var serverCertificate = new X509Certificate2(certificate);
        using var customChain = new X509Chain();
        customChain.ChainPolicy.TrustMode = X509ChainTrustMode.CustomRootTrust;
        customChain.ChainPolicy.RevocationMode = X509RevocationMode.NoCheck;
        customChain.ChainPolicy.CustomTrustStore.AddRange(_trustedServerRoots);

        if (chain is not null)
        {
            foreach (var element in chain.ChainElements)
            {
                if (!string.Equals(element.Certificate.Thumbprint, serverCertificate.Thumbprint, StringComparison.OrdinalIgnoreCase))
                    customChain.ChainPolicy.ExtraStore.Add(element.Certificate);
            }
        }

        return customChain.Build(serverCertificate);
    }

    private async Task ReadLoopAsync(CancellationToken ct)
    {
        var buffer = new byte[1024 * 128];
        var messageBuf = new StringBuilder();

        try
        {
            while (!ct.IsCancellationRequested && _ws?.State == WebSocketState.Open)
            {
                var result = await _ws.ReceiveAsync(new ArraySegment<byte>(buffer), ct);
                messageBuf.Append(Encoding.UTF8.GetString(buffer, 0, result.Count));

                if (result.EndOfMessage)
                {
                    var text = messageBuf.ToString();
                    messageBuf.Clear();
                    ProcessMessage(text);
                }
            }
        }
        catch
        {
            // conexão fechada
        }

        if (!_closed)
            HandleClose();
    }

    private void ProcessMessage(string text)
    {
        using var doc = JsonDocument.Parse(text);
        var root = doc.RootElement;

        if (root.TryGetProperty("notification", out var notif))
        {
            var id = notif.GetProperty("id").GetString() ?? "";
            var action = notif.GetProperty("action").GetString() ?? "";
            var result = JsonSerializer.Deserialize<object?>(notif.GetProperty("result").GetRawText(), JsonOpts);

            lock (_liveHandlers)
            {
                if (_liveHandlers.TryGetValue(id, out var handler))
                    handler(id, action, result);
            }
            return;
        }

        if (root.TryGetProperty("id", out var idEl) && idEl.ValueKind == JsonValueKind.Number)
        {
            var rpcId = idEl.GetInt32();
            var response = JsonSerializer.Deserialize<RpcResponse>(text, JsonOpts);

            lock (_pending)
            {
                if (_pending.TryGetValue(rpcId, out var tcs))
                {
                    _pending.Remove(rpcId);
                    tcs.TrySetResult(response!);
                }
            }
        }
    }

    private void HandleClose()
    {
        lock (_pending)
        {
            foreach (var tcs in _pending.Values)
                tcs.TrySetException(new AxonSdkException("conexão fechada"));
            _pending.Clear();
        }
        _handshakeReceived = false;

        if (!_closed && _reconnect)
            _ = ReconnectLoopAsync(0);
    }

    private async Task ReconnectLoopAsync(int attempt)
    {
        if (attempt >= _maxReconnectAttempts) return;
        await Task.Delay(_reconnectInterval);
        try
        {
            await OpenAsync();
            if (!string.IsNullOrEmpty(_namespace))
                await CallRawAsync("use", [_namespace, _database]);
            if (!string.IsNullOrEmpty(_auth))
                await CallRawAsync("authenticate", [_auth]);
        }
        catch
        {
            await ReconnectLoopAsync(attempt + 1);
        }
    }

    private async Task<RpcResponse> CallRawAsync(string method, object?[]? paramsList)
    {
        if (_ws?.State != WebSocketState.Open)
            throw new AxonSdkException("conexão não aberta");

        int id;
        TaskCompletionSource<RpcResponse> tcs;
        lock (this)
        {
            id = _nextId++;
            tcs = new TaskCompletionSource<RpcResponse>(TaskCreationOptions.RunContinuationsAsynchronously);
            _pending[id] = tcs;
        }

        var encodedParams = paramsList?.Select(Codec.EncodeTyped).ToArray();
        var request = new RpcRequest(id, method, encodedParams, Protocol.PROTOCOL_VERSION);
        var json = JsonSerializer.Serialize(request, JsonOpts);
        var bytes = Encoding.UTF8.GetBytes(json);

        try
        {
            await _ws.SendAsync(new ArraySegment<byte>(bytes), WebSocketMessageType.Text, true, CancellationToken.None);
        }
        catch (Exception ex)
        {
            lock (_pending) { _pending.Remove(id); }
            tcs.TrySetException(ex);
        }

        using var cts = new CancellationTokenSource(_timeout);
        using (cts.Token.Register(() => tcs.TrySetException(new AxonSdkException($"timeout em {method}"))))
        {
            return await tcs.Task;
        }
    }

    private async Task<object?> CallAsync(string method, object?[]? paramsList, int maxRetries = 3)
    {
        for (int attempt = 0; attempt < maxRetries; attempt++)
        {
            try
            {
                var response = await CallRawAsync(method, paramsList);
                if (response.Error != null)
                {
                    var ex = ErrorFactory.FromRpcError(response.Error);
                    if (ex is NotLeaderException nle && !string.IsNullOrEmpty(nle.LeaderAddress) && attempt < maxRetries - 1)
                    {
                        var scheme = _url.StartsWith("wss://") ? "wss://" : "ws://";
                        var newUrl = $"{scheme}{nle.LeaderAddress}/rpc/ws";
                        await ReconnectToAsync(newUrl);
                        continue;
                    }
                    throw ex;
                }
                return response.Result;
            }
            catch (AxonSdkException) when (attempt < maxRetries - 1 && _reconnect)
            {
                await Task.Delay(_reconnectInterval);
                await OpenAsync();
            }
        }
        throw new AxonSdkException("falha após tentativas de reconexão");
    }

    private async Task ReconnectToAsync(string newUrl)
    {
        if (_ws != null)
        {
            try { _ws.CloseAsync(WebSocketCloseStatus.NormalClosure, "reconnect", CancellationToken.None).Wait(); }
            catch { }
            _ws.Dispose();
            _ws = null;
        }
        _handshakeReceived = false;
        _hello = null;

        var oldNs = _namespace;
        var oldDb = _database;
        var oldAuth = _auth;

        _url = newUrl;
        await OpenAsync();
        if (!string.IsNullOrEmpty(oldNs))
            await CallRawAsync("use", [oldNs, oldDb]);
        if (!string.IsNullOrEmpty(oldAuth))
            await CallRawAsync("authenticate", [oldAuth]);
    }

    public async Task<bool> PingAsync() => (bool)(await CallAsync("ping", [null]))!;

    public async Task<Dictionary<string, string>> UseAsync(string ns, string db)
    {
        var result = await CallAsync("use", [ns, db]);
        var dict = JsonSerializer.Deserialize<Dictionary<string, string>>(JsonSerializer.Serialize(result, JsonOpts), JsonOpts)!;
        _namespace = dict.GetValueOrDefault("namespace", ns);
        _database = dict.GetValueOrDefault("database", db);
        return dict;
    }

    public async Task<object?> QueryAsync(string sql, Dictionary<string, object?>? vars = null)
    {
        if (vars != null)
            return await CallAsync("query", [sql, vars]);
        return await CallAsync("query", [sql]);
    }

    public async Task<string> SigninAsync(string user, string pass, string? access = null)
    {
        var cred = new Dictionary<string, object?> { ["user"] = user, ["pass"] = pass };
        if (access != null) cred["access"] = access;
        var token = (string)(await CallAsync("signin", [cred]))!;
        _auth = token;
        return token;
    }

    public async Task AuthenticateAsync(string token)
    {
        await CallAsync("authenticate", [token]);
        _auth = token;
    }

    public async Task<string> SignupAsync(string user, string pass)
    {
        var token = (string)(await CallAsync("signup", [new Dictionary<string, object?> { ["user"] = user, ["pass"] = pass }]))!;
        _auth = token;
        return token;
    }

    public async Task InvalidateAsync()
    {
        await CallAsync("invalidate", []);
        _auth = null;
    }

    public async Task<CertificateChallenge> CertificateBeginAsync(string store)
    {
        var result = await CallAsync(Protocol.METHOD_CERTIFICATE_BEGIN, [new Dictionary<string, object?> { ["store"] = store }]);
        return JsonSerializer.Deserialize<CertificateChallenge>(JsonSerializer.Serialize(result, JsonOpts), JsonOpts)
            ?? throw new AxonSdkException("Invalid certificate.begin response");
    }

    public async Task<string> CertificateCompleteAsync(CertificateCompletion completion)
    {
        ArgumentNullException.ThrowIfNull(completion);
        var result = await CallAsync(Protocol.METHOD_CERTIFICATE_COMPLETE,
        [
            new Dictionary<string, object?>
            {
                ["id"] = completion.Id,
                ["challenge"] = completion.Challenge,
                ["store"] = completion.Store,
                ["user"] = completion.User,
                ["chain"] = completion.Chain,
                ["signature"] = completion.Signature,
            }
        ]);
        return JsonSerializer.Deserialize<string>(JsonSerializer.Serialize(result, JsonOpts), JsonOpts)
            ?? throw new AxonSdkException("Invalid certificate.complete response");
    }

    public async Task<string> VersionAsync() => (string)(await CallAsync("version", []))!;

    public async Task<object?> SelectAsync(string what) => await CallAsync("select", [what]);
    public async Task<object?> CreateAsync(string what, Dictionary<string, object?> data) => await CallAsync("create", [what, data]);
    public async Task<object?> UpdateAsync(string what, Dictionary<string, object?> data) => await CallAsync("update", [what, data]);
    public async Task<object?> UpsertAsync(string what, Dictionary<string, object?> data) => await CallAsync("upsert", [what, data]);
    public async Task<object?> DeleteAsync(string what) => await CallAsync("delete", [what]);
    public async Task<object?> InsertAsync(string table, Dictionary<string, object?> data) => await CallAsync("insert", [table, data]);
    public async Task<object?> RelateAsync(string from, string kind, string to, Dictionary<string, object?>? data = null)
        => await CallAsync("relate", [from, kind, to, data ?? new()]);

    public async Task LetVarAsync(string name, object? value)
    {
        var clean = name.StartsWith("$") ? name[1..] : name;
        await CallRawAsync("let", [clean, value]);
    }

    public async Task UnsetAsync(string name)
    {
        var clean = name.StartsWith("$") ? name[1..] : name;
        await CallRawAsync("unset", [clean]);
    }

    public async Task BeginAsync() => await CallAsync("begin", []);
    public async Task CommitAsync() => await CallAsync("commit", []);
    public async Task CancelAsync() => await CallAsync("cancel", []);

    public async Task<object?> KvGetAsync(string ns, string db, string key) => await CallAsync("kv_get", [ns, db, key]);
    public async Task<object?> KvSetAsync(string ns, string db, string key, object? value, int? ttl = null)
        => await CallAsync("kv_set", [ns, db, key, value, ttl]);
    public async Task<bool> KvDelAsync(string ns, string db, string key) => (bool)(await CallAsync("kv_del", [ns, db, key]))!;
    public async Task<List<KvEntry>> KvScanAsync(string ns, string db, string prefix)
    {
        var result = await CallAsync("kv_scan", [ns, db, prefix]);
        var json = JsonSerializer.Serialize(result, JsonOpts);
        return JsonSerializer.Deserialize<List<KvEntry>>(json, JsonOpts) ?? [];
    }

    public async Task<string> LiveAsync(string table, LiveHandler handler, bool diff = false)
    {
        var id = (string)(await CallAsync("live", [table, diff]))!;
        lock (_liveHandlers) { _liveHandlers[id] = handler; }
        return id;
    }

    public async Task<bool> KillAsync(string id)
    {
        var ok = (bool)(await CallAsync("kill", [id]))!;
        if (ok) { lock (_liveHandlers) { _liveHandlers.Remove(id); } }
        return ok;
    }

    public string Namespace => _namespace;
    public string Database => _database;
    public bool IsConnected => _ws?.State == WebSocketState.Open;
    public HelloPayload? HelloInfo => _hello;

    public void Dispose()
    {
        _closed = true;
        _readCts?.Cancel();
        _liveHandlers.Clear();
        _ws?.Dispose();
        _ws = null;
    }
}

public class AxonClientOptions
{
    public TimeSpan Timeout { get; set; } = TimeSpan.FromSeconds(30);
    public bool Reconnect { get; set; } = true;
    public TimeSpan ReconnectInterval { get; set; } = TimeSpan.FromSeconds(1);
    public int MaxReconnectAttempts { get; set; } = 10;
    public X509CertificateCollection ClientCertificates { get; set; } = [];
    public X509Certificate2Collection TrustedServerRoots { get; set; } = [];
}
