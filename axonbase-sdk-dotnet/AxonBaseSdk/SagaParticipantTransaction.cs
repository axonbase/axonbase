namespace AxonBaseSdk;

/// <summary>Joins an active SAGA while controlling only this client's local transaction.</summary>
public sealed class SagaParticipantTransaction
{
    private readonly AxonClient _axon;

    public SagaParticipantTransaction(AxonClient axon, string correlationId)
    {
        _axon = axon ?? throw new ArgumentNullException(nameof(axon));
        CorrelationId = correlationId ?? throw new ArgumentNullException(nameof(correlationId));
    }

    public string CorrelationId { get; }
    public bool IsBegun { get; private set; }
    public bool IsFinished { get; private set; }

    public async Task BeginAsync()
    {
        if (IsBegun)
            throw new InvalidOperationException($"Transaction already begun: {CorrelationId}");

        await _axon.BeginAsync();
        try
        {
            await _axon.LetVarAsync("saga_corr", CorrelationId);
            IsBegun = true;
        }
        catch
        {
            try
            {
                await _axon.CancelAsync();
            }
            catch
            {
                // Preserve the error that prevented the session correlation from being set.
            }
            throw;
        }
    }

    public async Task<object?> StepAsync(string axonql)
    {
        EnsureActive();
        return await _axon.QueryAsync(axonql);
    }

    public async Task CommitAsync()
    {
        EnsureActive();
        await _axon.CommitAsync();
        IsFinished = true;
    }

    public async Task RollbackAsync()
    {
        if (!IsBegun || IsFinished)
            return;

        await _axon.CancelAsync();
        IsFinished = true;
    }

    private void EnsureActive()
    {
        if (!IsBegun)
            throw new InvalidOperationException("Transaction not begun");
        if (IsFinished)
            throw new InvalidOperationException("Transaction already finished");
    }
}
