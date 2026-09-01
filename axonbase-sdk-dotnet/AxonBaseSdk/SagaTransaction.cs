namespace AxonBaseSdk;

/// <summary>Client-side helper for an AxonBase SAGA transaction.</summary>
public sealed class SagaTransaction
{
    private readonly AxonClient _axon;

    public SagaTransaction(AxonClient axon, string sagaName, string correlationId)
    {
        _axon = axon ?? throw new ArgumentNullException(nameof(axon));
        SagaName = sagaName ?? throw new ArgumentNullException(nameof(sagaName));
        CorrelationId = correlationId ?? throw new ArgumentNullException(nameof(correlationId));
    }

    public string SagaName { get; }
    public string CorrelationId { get; }
    public bool IsBegun { get; private set; }
    public bool IsFinished { get; private set; }

    public async Task BeginAsync()
    {
        if (IsBegun)
            throw new InvalidOperationException($"Saga already begun: {CorrelationId}");

        var result = await _axon.QueryAsync(
            $"BEGIN SAGA {Escape(SagaName)} WITH CORRELATION '{Escape(CorrelationId)}'");
        if (Status(result) == "RUNNING")
        {
            IsBegun = true;
            return;
        }

        throw new AxonSdkException($"Failed to begin saga: {result}");
    }

    public async Task<object?> StepAsync(string axonql)
    {
        EnsureActive();
        await _axon.LetVarAsync("saga_corr", CorrelationId);
        return await _axon.QueryAsync(axonql);
    }

    public async Task CommitAsync()
    {
        EnsureActive();
        var result = await _axon.QueryAsync(
            $"COMMIT SAGA {Escape(SagaName)} WITH CORRELATION '{Escape(CorrelationId)}'");
        IsFinished = true;
        if (Status(result) != "COMMITTED")
            throw new AxonSdkException($"Saga commit failed: {result}");
    }

    public async Task RollbackAsync()
    {
        if (!IsBegun || IsFinished)
            return;

        await _axon.QueryAsync(
            $"CANCEL SAGA {Escape(SagaName)} WITH CORRELATION '{Escape(CorrelationId)}'");
        IsFinished = true;
    }

    public Task<object?> DescribeAsync() => _axon.QueryAsync(
        $"SHOW SAGA TRANSACTION {Escape(SagaName)} '{Escape(CorrelationId)}'");

    internal static string Escape(string value) => value.Replace("\\", "\\\\").Replace("'", "''");

    private void EnsureActive()
    {
        if (!IsBegun)
            throw new InvalidOperationException("Saga not begun");
        if (IsFinished)
            throw new InvalidOperationException("Saga already finished");
    }

    private static string? Status(object? result)
    {
        if (result is null)
            return null;

        using var document = System.Text.Json.JsonDocument.Parse(
            System.Text.Json.JsonSerializer.Serialize(result));
        if (document.RootElement.ValueKind != System.Text.Json.JsonValueKind.Object
            || !document.RootElement.TryGetProperty("status", out var status)
            || status.ValueKind != System.Text.Json.JsonValueKind.String)
            return null;

        return status.GetString();
    }
}
