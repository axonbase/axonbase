"""Optional LangChain adapter for :class:`AxonBaseVectorStore`."""

from typing import Any

from ..vectorstore import AxonBaseVectorStore, VectorRecord

try:
    from langchain_core.vectorstores import VectorStore
except ImportError:
    VectorStore = object
    _LANGCHAIN_AVAILABLE = False
else:
    _LANGCHAIN_AVAILABLE = True


class AxonBaseLangChainVectorStore(VectorStore):
    """Async-first LangChain adapter backed by an existing AxonBase store.

    Construct it with an existing :class:`AxonBaseVectorStore`, then use
    ``aadd_documents`` and ``asimilarity_search``. Synchronous methods raise
    because blocking on an async AxonBase client is unsafe in an event loop.
    """

    def __init__(self, store: AxonBaseVectorStore, embeddings: Any) -> None:
        if not _LANGCHAIN_AVAILABLE:
            raise ImportError(
                "LangChain support requires: pip install 'axonbase-sdk[langchain]'"
            )
        self.store = store
        self._embeddings = embeddings

    @property
    def embeddings(self) -> Any:
        return self._embeddings

    def add_documents(self, documents: list[Any], **kwargs: Any) -> list[str]:
        raise NotImplementedError("Use aadd_documents() with this async-first adapter.")

    async def aadd_documents(self, documents, **kwargs: Any) -> list[str]:
        ids = kwargs.get("ids") or [
            str(getattr(document, "id", None) or index) for index, document in enumerate(documents)
        ]
        if len(ids) != len(documents):
            raise ValueError("ids must contain one value for every document")
        ids = [str(id) for id in ids]
        vectors = await self.embeddings.aembed_documents(
            [document.page_content for document in documents]
        )
        await self.store.upsert([
            VectorRecord(id, document.page_content, vector, document.metadata)
            for id, document, vector in zip(ids, documents, vectors)
        ])
        return list(ids)

    def similarity_search(self, query: str, k: int = 4, **kwargs: Any) -> list[Any]:
        raise NotImplementedError("Use asimilarity_search() with this async-first adapter.")

    async def asimilarity_search(self, query: str, k: int = 4, **kwargs: Any):
        from langchain_core.documents import Document

        results = await self.store.similarity_search(query, _LangChainEmbedder(self.embeddings), k)
        return [Document(page_content=result.content, metadata=result.metadata) for result in results]

    @classmethod
    def from_texts(cls, *args: Any, **kwargs: Any):
        raise NotImplementedError(
            "Construct AxonBaseLangChainVectorStore with an existing "
            "AxonBaseVectorStore; use its async methods."
        )


class _LangChainEmbedder:
    def __init__(self, embeddings: Any) -> None:
        self.embeddings = embeddings

    async def embed_query(self, query: str):
        return await self.embeddings.aembed_query(query)
