using System.Text.Json;
using System.Net.Security;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using AxonBaseSdk;

var version = Protocol.PROTOCOL_VERSION;
Console.WriteLine($"PROTOCOL_VERSION = {version} (expected: 1)");
if (version != 1)
    throw new Exception("PROTOCOL_VERSION must be 1");

if (!Protocol.METHODS.Contains("query"))
    throw new Exception("METHODS must contain 'query'");

if (!Protocol.METHODS.Contains("kv_get"))
    throw new Exception("METHODS must contain 'kv_get'");

if (Protocol.METHOD_CERTIFICATE_BEGIN != "certificate.begin" || !Protocol.METHODS.Contains(Protocol.METHOD_CERTIFICATE_BEGIN))
    throw new Exception("METHODS must contain 'certificate.begin'");

if (Protocol.METHOD_CERTIFICATE_COMPLETE != "certificate.complete" || !Protocol.METHODS.Contains(Protocol.METHOD_CERTIFICATE_COMPLETE))
    throw new Exception("METHODS must contain 'certificate.complete'");

Console.WriteLine("Protocol constants OK");

var notLeader = ErrorFactory.FromRpcError(new RpcError(Protocol.ERR_NOT_LEADER, "not leader", Leader: "n1", LeaderAddress: "addr"));
if (notLeader is not NotLeaderException nle)
    throw new Exception("Expected NotLeaderException");
if (nle.Leader != "n1")
    throw new Exception("Unexpected leader");

var noQuorum = ErrorFactory.FromRpcError(new RpcError(Protocol.ERR_NO_QUORUM, "no quorum"));
if (noQuorum is not NoQuorumException)
    throw new Exception("Expected NoQuorumException");

var authErr = ErrorFactory.FromRpcError(new RpcError(Protocol.ERR_AUTH, "auth"));
if (authErr is not AuthException)
    throw new Exception("Expected AuthException");

Console.WriteLine("Typed errors OK");

var encoded = Codec.EncodeTyped(42);
if ((int)encoded! != 42)
    throw new Exception("EncodeTyped(42) failed");

encoded = Codec.EncodeTyped("hello");
if ((string)encoded! != "hello")
    throw new Exception("EncodeTyped('hello') failed");

Console.WriteLine("Codec OK");

var request = new RpcRequest(1, "ping", [], Protocol.PROTOCOL_VERSION);
var json = JsonSerializer.Serialize(request, new JsonSerializerOptions { PropertyNamingPolicy = JsonNamingPolicy.CamelCase });
if (!json.Contains("\"method\":\"ping\""))
    throw new Exception("RpcRequest serialization failed");

Console.WriteLine("JSON serialization OK");

var helloJson = """{"hello": {"protocol": 1, "server": "0.1.0-SNAPSHOT", "methods": ["ping", "query"]}}""";
var hello = JsonSerializer.Deserialize<HelloFrame>(helloJson, new JsonSerializerOptions { PropertyNamingPolicy = JsonNamingPolicy.CamelCase });
if (hello?.Hello.Protocol != 1)
    throw new Exception("HelloFrame deserialization failed");

Console.WriteLine("HelloFrame OK");

var entriesJson = """[{"key": "k1", "value": "v1"}, {"key": "k2", "value": 42}]""";
var entries = JsonSerializer.Deserialize<List<KvEntry>>(entriesJson, new JsonSerializerOptions { PropertyNamingPolicy = JsonNamingPolicy.CamelCase });
if (entries?.Count != 2)
    throw new Exception("KvEntry list deserialization failed");

Console.WriteLine("KvEntry OK");

var challengeJson = """{"id":"challenge-id","challenge":"nonce","expires_at":"2026-01-01T00:00:00Z"}""";
var challenge = JsonSerializer.Deserialize<CertificateChallenge>(challengeJson, new JsonSerializerOptions { PropertyNamingPolicy = JsonNamingPolicy.CamelCase });
if (challenge is not { Id: "challenge-id", Challenge: "nonce", ExpiresAt: "2026-01-01T00:00:00Z" })
    throw new Exception("CertificateChallenge deserialization failed");

var completion = new CertificateCompletion(
    "challenge-id", "nonce", "icp-brasil", "12345678901", ["base64-der-certificate"], "base64-signature");
var completionJson = JsonSerializer.Serialize(completion, new JsonSerializerOptions { PropertyNamingPolicy = JsonNamingPolicy.CamelCase });
if (completionJson != """{"id":"challenge-id","challenge":"nonce","store":"icp-brasil","user":"12345678901","chain":["base64-der-certificate"],"signature":"base64-signature"}""")
    throw new Exception("CertificateCompletion serialization failed");

Console.WriteLine("Certificate contracts OK");

using var key = RSA.Create(2048);
var requestCertificate = new CertificateRequest("CN=client", key, HashAlgorithmName.SHA256, RSASignaturePadding.Pkcs1);
using var certificate = requestCertificate.CreateSelfSigned(DateTimeOffset.UtcNow.AddDays(-1), DateTimeOffset.UtcNow.AddDays(1));
var clientCertificates = new X509CertificateCollection();
clientCertificates.Add(certificate);
var client = new AxonClient("wss://localhost:443", new AxonClientOptions
{
    ClientCertificates = clientCertificates,
});
using var webSocket = client.CreateWebSocket();
if (webSocket.Options.ClientCertificates.Count != 1
    || ((X509Certificate2)webSocket.Options.ClientCertificates[0]).Thumbprint != certificate.Thumbprint)
    throw new Exception("Client certificates were not applied to the WebSocket");

Console.WriteLine("mTLS options OK");

var certificateNotBefore = DateTimeOffset.UtcNow.AddDays(-1);
var certificateNotAfter = DateTimeOffset.UtcNow.AddDays(1);
using var trustedRootKey = RSA.Create(2048);
var trustedRootRequest = new CertificateRequest("CN=trusted-root", trustedRootKey, HashAlgorithmName.SHA256, RSASignaturePadding.Pkcs1);
trustedRootRequest.CertificateExtensions.Add(new X509BasicConstraintsExtension(true, false, 0, true));
trustedRootRequest.CertificateExtensions.Add(new X509KeyUsageExtension(X509KeyUsageFlags.KeyCertSign | X509KeyUsageFlags.CrlSign, true));
using var trustedRoot = trustedRootRequest.CreateSelfSigned(certificateNotBefore, certificateNotAfter.AddDays(1));

using var serverKey = RSA.Create(2048);
var serverRequest = new CertificateRequest("CN=localhost", serverKey, HashAlgorithmName.SHA256, RSASignaturePadding.Pkcs1);
serverRequest.CertificateExtensions.Add(new X509BasicConstraintsExtension(false, false, 0, true));
serverRequest.CertificateExtensions.Add(new X509KeyUsageExtension(X509KeyUsageFlags.DigitalSignature | X509KeyUsageFlags.KeyEncipherment, true));
serverRequest.CertificateExtensions.Add(new X509EnhancedKeyUsageExtension([new Oid("1.3.6.1.5.5.7.3.1")], true));
using var serverCertificate = serverRequest.Create(trustedRoot, certificateNotBefore, certificateNotAfter, RandomNumberGenerator.GetBytes(16));

using var unrelatedRootKey = RSA.Create(2048);
var unrelatedRootRequest = new CertificateRequest("CN=unrelated-root", unrelatedRootKey, HashAlgorithmName.SHA256, RSASignaturePadding.Pkcs1);
unrelatedRootRequest.CertificateExtensions.Add(new X509BasicConstraintsExtension(true, false, 0, true));
unrelatedRootRequest.CertificateExtensions.Add(new X509KeyUsageExtension(X509KeyUsageFlags.KeyCertSign | X509KeyUsageFlags.CrlSign, true));
using var unrelatedRoot = unrelatedRootRequest.CreateSelfSigned(certificateNotBefore, certificateNotAfter);

var trustedServerClient = new AxonClient("wss://localhost:443", new AxonClientOptions
{
    TrustedServerRoots = [trustedRoot],
});
using var trustedServerWebSocket = trustedServerClient.CreateWebSocket();
var validateServer = trustedServerWebSocket.Options.RemoteCertificateValidationCallback
    ?? throw new Exception("Trusted server roots did not configure certificate validation");
if (!validateServer(trustedServerWebSocket, serverCertificate, null, SslPolicyErrors.None))
    throw new Exception("Trusted server root was rejected");

var unrelatedServerClient = new AxonClient("wss://localhost:443", new AxonClientOptions
{
    TrustedServerRoots = [unrelatedRoot],
});
using var unrelatedServerWebSocket = unrelatedServerClient.CreateWebSocket();
var rejectServer = unrelatedServerWebSocket.Options.RemoteCertificateValidationCallback
    ?? throw new Exception("Unrelated server root did not configure certificate validation");
if (rejectServer(unrelatedServerWebSocket, serverCertificate, null, SslPolicyErrors.None))
    throw new Exception("Unrelated server root was accepted");

using var platformValidationClient = new AxonClient("wss://localhost:443");
using var platformValidationWebSocket = platformValidationClient.CreateWebSocket();
if (platformValidationWebSocket.Options.RemoteCertificateValidationCallback is not null)
    throw new Exception("Platform certificate validation was overridden without custom roots");

Console.WriteLine("Custom server CA trust options OK");

var saga = new SagaTransaction(new AxonClient("ws://localhost:8000/rpc/ws"), "order", "corr-1");
if (saga.IsBegun || saga.IsFinished || saga.SagaName != "order" || saga.CorrelationId != "corr-1")
    throw new Exception("SagaTransaction initial state failed");
if (SagaTransaction.Escape("a\\b'c") != "a\\\\b''c")
    throw new Exception("SagaTransaction literal escaping failed");
try
{
    await saga.StepAsync("UPDATE orders:o1 SET total = 200");
    throw new Exception("SagaTransaction allowed a step before begin");
}
catch (InvalidOperationException) { }

Console.WriteLine("SagaTransaction checks OK");

var participant = new SagaParticipantTransaction(new AxonClient("ws://localhost:8000/rpc/ws"), "corr-1");
if (participant.IsBegun || participant.IsFinished || participant.CorrelationId != "corr-1")
    throw new Exception("SagaParticipantTransaction initial state failed");
try
{
    await participant.StepAsync("UPDATE orders:o1 SET total = 200");
    throw new Exception("SagaParticipantTransaction allowed a step before begin");
}
catch (InvalidOperationException) { }
await participant.RollbackAsync();

Console.WriteLine("SagaParticipantTransaction checks OK");
Console.WriteLine("All .NET SDK checks passed");
