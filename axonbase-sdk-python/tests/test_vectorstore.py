from unittest.mock import patch

import pytest

from axonbase.vectorstore import AxonBaseVectorStore, VectorRecord


class FakeAxon:
    def __init__(self, results=None):
        self.queries = []
        self.results = results or []

    async def query(self, sql, vars=None):
        self.queries.append((sql, vars))
        return self.results.pop(0) if self.results else []


class FakeEmbedder:
    async def embed_query(self, query):
        return [1.0, 0.0, 0.0]


@pytest.mark.asyncio
async def test_ensure_schema_defines_table_and_hnsw_index():
    axon = FakeAxon()
    store = AxonBaseVectorStore(axon, "chunks", dimensions=3)

    await store.ensure_schema()

    assert axon.queries == [
        ("DEFINE TABLE chunks SCHEMALESS", None),
        ("DEFINE INDEX chunks_embedding_hnsw ON TABLE chunks COLUMNS embedding HNSW DIMENSION 3 DIST cosine", None),
    ]


@pytest.mark.asyncio
async def test_upsert_uses_safe_record_ids_and_bound_content():
    axon = FakeAxon()
    store = AxonBaseVectorStore(axon, "chunks", dimensions=3)
    record = VectorRecord(
        id="doc_1-chunk.0",
        content="The content",
        embedding=[1.0, 0.0, 0.0],
        metadata={"topic": "tests"},
        source_uri="file:///document.txt",
        content_hash="abc123",
        chunk_index=0,
    )

    await store.upsert([record])

    assert axon.queries == [
        ("UPSERT chunks:doc_1-chunk.0 CONTENT $record", {
            "record": {
                "content": "The content",
                "metadata": {"topic": "tests"},
                "embedding": [1.0, 0.0, 0.0],
                "source_uri": "file:///document.txt",
                "content_hash": "abc123",
                "chunk_index": 0,
            }
        })
    ]


@pytest.mark.asyncio
async def test_dimension_validation_happens_before_queries():
    axon = FakeAxon()
    store = AxonBaseVectorStore(axon, "chunks", dimensions=3)

    with pytest.raises(ValueError, match="expected 3 dimensions"):
        await store.upsert([VectorRecord("one", "content", [1.0, 0.0])])
    with pytest.raises(ValueError, match="expected 3 dimensions"):
        await store.similarity_search_by_vector([1.0, 0.0])

    assert axon.queries == []


@pytest.mark.asyncio
async def test_cosine_search_uses_hnsw_distance_and_maps_sorted_results():
    axon = FakeAxon(results=[[{
        "id": "chunks:far", "content": "Far", "metadata": {"rank": 2}, "distance": 0.8,
    }, {
        "id": "chunks:near", "content": "Near", "metadata": {"rank": 1}, "distance": 0.1,
    }]])
    store = AxonBaseVectorStore(axon, "chunks", dimensions=3)

    results = await store.similarity_search_by_vector([1.0, 0.0, 0.0], limit=2)

    sql, variables = axon.queries[0]
    assert sql == (
        "SELECT id, content, metadata, source_uri, content_hash, chunk_index, "
        "vector::distance::cosine(embedding, $vector) AS distance FROM chunks "
        "ORDER BY vector::distance::cosine(embedding, $vector) LIMIT 2"
    )
    assert variables == {"vector": [1.0, 0.0, 0.0]}
    assert [result.id for result in results] == ["near", "far"]
    assert results[0].score == pytest.approx(0.9)


@pytest.mark.asyncio
async def test_search_delegates_embedding_to_async_embedder():
    axon = FakeAxon(results=[[]])
    store = AxonBaseVectorStore(axon, "chunks", dimensions=3)

    assert await store.similarity_search("question", FakeEmbedder()) == []


def test_invalid_identifiers_are_rejected():
    with pytest.raises(ValueError, match="collection"):
        AxonBaseVectorStore(FakeAxon(), "chunks; DELETE", dimensions=3)
    with pytest.raises(ValueError, match="record id"):
        VectorRecord("bad id", "content", [1.0, 0.0, 0.0])


def test_openai_adapter_reports_missing_optional_dependency():
    from axonbase.openai_embeddings import OpenAIEmbedder

    with patch("builtins.__import__", side_effect=ImportError("missing openai")):
        with pytest.raises(ImportError, match=r"axonbase-sdk\[openai\]"):
            OpenAIEmbedder()
