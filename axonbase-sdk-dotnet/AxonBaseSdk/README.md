# AxonBase .NET SDK

WebSocket JSON-RPC client for AxonBase.

```csharp
using AxonBaseSdk;

await using var client = await AxonClient.ConnectAsync("ws://localhost:8000");
```

See https://github.com/axonbase/axonbase for documentation and examples.
