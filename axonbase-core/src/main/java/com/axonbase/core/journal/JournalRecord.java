package com.axonbase.core.journal;

import com.axonbase.core.cluster.CommittedBatch;

/** Um lote do journal com o índice dele. */
public record JournalRecord(long index, CommittedBatch batch) {
}
