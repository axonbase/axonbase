import os
import uuid

import pytest


pytestmark = pytest.mark.skipif(
    not (os.getenv("AXON_URL") and os.getenv("OPENAI_API_KEY")),
    reason="AXON_URL and OPENAI_API_KEY are required for vector integration tests.",
)


@pytest.mark.asyncio
async def test_openai_embeddings_and_hnsw_search_against_axonbase():
    """Exercise one small OpenAI embedding batch and an AxonBase HNSW query."""
    from axonbase import Axon
    from axonbase.openai_embeddings import OpenAIEmbedder
    from axonbase.vectorstore import AxonBaseVectorStore, VectorRecord

    embedder = OpenAIEmbedder()
    embeddings = await embedder.embed_documents([
        "AxonBase vector search test",
        "Find the AxonBase vector search test",
    ])
    collection = f"sdk_vectors_{uuid.uuid4().hex}"
    axon = await Axon.connect(os.environ["AXON_URL"])
    try:
        await axon.use("app", "main")
        store = AxonBaseVectorStore(axon, collection, dimensions=len(embeddings[0]))
        await store.ensure_schema()
        await store.upsert([
            VectorRecord("sample", "AxonBase vector search test", embeddings[0], {"test": True})
        ])
        results = await store.similarity_search_by_vector(embeddings[1], limit=1)
        assert results and results[0].id == "sample"
    finally:
        await axon.close()
