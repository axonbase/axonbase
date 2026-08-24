package com.axonbase.core.cluster;
/** Fronteira entre commit do Datastore e confirmação distribuída. */
public interface CommitCoordinator { long confirm(String nodeId, CommittedBatch batch); }
