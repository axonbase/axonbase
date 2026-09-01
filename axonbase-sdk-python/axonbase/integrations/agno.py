"""Optional Agno adapter for :class:`AxonBaseVectorStore`."""

from typing import Any, Optional

from ..vectorstore import AxonBaseVectorStore, VectorRecord

try:
    from agno.vectordb.base import VectorDb
except ImportError:
    VectorDb = object
    _AGNO_AVAILABLE = False
else:
    _AGNO_AVAILABLE = True


class AxonBaseAgnoVectorDb(VectorDb):
    """Async-first Agno VectorDb adapter backed by an existing AxonBase store.

    Construct it with an existing :class:`AxonBaseVectorStore`, then use
    ``async_create``, ``async_insert``, ``async_upsert``, ``async_search``,
    and ``async_delete_by_id``. The synchronous methods are concrete but raise
    because blocking on AxonBase's async client is unsafe in an event loop.
    """

    def __init__(self, store: AxonBaseVectorStore, embedder: Any, **kwargs: Any) -> None:
        if not _AGNO_AVAILABLE:
            raise ImportError("Agno support requires: pip install 'axonbase-sdk[agno]'")
        super().__init__(**kwargs)
        self.store = store
        self.embedder = embedder
        self._created = False

    async def async_create(self) -> None:
        await self.store.ensure_schema()
        self._created = True

    def create(self) -> None:
        raise NotImplementedError("Use async_create() with this async-first adapter.")

    def name_exists(self, name: str) -> bool:
        raise NotImplementedError("Use async_exists() with this async-first adapter.")

    def async_name_exists(self, name: str) -> bool:
        raise NotImplementedError("Use async_exists() with this async-first adapter.")

    def id_exists(self, id: str) -> bool:
        raise NotImplementedError("Use async_exists() with this async-first adapter.")

    def content_hash_exists(self, content_hash: str, user_id: Optional[str] = None) -> bool:
        raise NotImplementedError("This async-first adapter does not provide synchronous existence checks.")

    def insert(self, content_hash: str, documents: list[Any], filters: Optional[dict[str, Any]] = None, user_id: Optional[str] = None) -> None:
        raise NotImplementedError("Use async_insert() with this async-first adapter.")

    async def async_insert(self, content_hash: str, documents: list[Any], filters: Optional[dict[str, Any]] = None, user_id: Optional[str] = None) -> None:
        await self._write(content_hash, documents, filters, user_id)

    def upsert_available(self) -> bool:
        return True

    def upsert(self, content_hash: str, documents: list[Any], filters: Optional[dict[str, Any]] = None, user_id: Optional[str] = None) -> None:
        raise NotImplementedError("Use async_upsert() with this async-first adapter.")

    async def async_upsert(self, content_hash: str, documents: list[Any], filters: Optional[dict[str, Any]] = None, user_id: Optional[str] = None) -> None:
        await self._write(content_hash, documents, filters, user_id)

    async def _write(self, content_hash: str, documents: list[Any], filters: Optional[dict[str, Any]], user_id: Optional[str]) -> None:
        records = []
        for index, document in enumerate(documents):
            content = getattr(document, "content", None) or getattr(document, "page_content", "")
            embedding = getattr(document, "embedding", None)
            if embedding is None:
                result = self.embedder.embed_query(content)
                embedding = await result if hasattr(result, '__await__') else result
            metadata = dict(getattr(document, "meta_data", None) or getattr(document, "metadata", {}) or {})
            if filters:
                metadata.update(filters)
            if user_id is not None:
                metadata["user_id"] = user_id
            records.append(VectorRecord(
                str(getattr(document, "id", None) or index), content, embedding, metadata,
                content_hash=content_hash,
            ))
        await self.store.upsert(records)

    def search(self, query: str, limit: int = 5, filters: Optional[Any] = None, user_id: Optional[str] = None) -> list[Any]:
        raise NotImplementedError("Use async_search() with this async-first adapter.")

    async def async_search(self, query: str, limit: int = 5, filters: Optional[Any] = None, user_id: Optional[str] = None) -> list[Any]:
        from agno.knowledge.document import Document

        if filters is not None or user_id is not None:
            raise NotImplementedError("Metadata and user filtering are not supported by AxonBaseVectorStore.")
        result = self.embedder.embed_query(query)
        vector = await result if hasattr(result, '__await__') else result
        results = await self.store.similarity_search_by_vector(vector, limit)
        return [Document(id=result.id, content=result.content, meta_data=result.metadata) for result in results]

    def drop(self) -> None:
        raise NotImplementedError("Dropping collections is not supported by this async-first adapter.")

    async def async_drop(self) -> None:
        raise NotImplementedError("Dropping collections is not supported by this async-first adapter.")

    def exists(self) -> bool:
        raise NotImplementedError("Use async_exists() with this async-first adapter.")

    async def async_exists(self) -> bool:
        return self._created

    def delete(self) -> bool:
        raise NotImplementedError("Use async_delete_by_id() with this async-first adapter.")

    def delete_by_id(self, id: str) -> bool:
        raise NotImplementedError("Use async_delete_by_id() with this async-first adapter.")

    async def async_delete_by_id(self, id: str) -> bool:
        from ..vectorstore import _validate_record_id

        _validate_record_id(id)
        await self.store.axon.query(f"DELETE {self.store.collection}:{id}")
        return True

    def delete_by_name(self, name: str) -> bool:
        raise NotImplementedError("Deletion by name is not supported by this adapter.")

    def delete_by_metadata(self, metadata: dict[str, Any]) -> bool:
        raise NotImplementedError("Deletion by metadata is not supported by this adapter.")

    def delete_by_content_id(self, content_id: str, user_id: Optional[str] = None) -> bool:
        raise NotImplementedError("Deletion by content ID is not supported by this adapter.")

    def get_supported_search_types(self) -> list[str]:
        return ["vector"]

    async def async_delete(self, id: str) -> None:
        await self.async_delete_by_id(id)
