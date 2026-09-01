"""Async vector storage backed by AxonBase HNSW indexes."""

from dataclasses import dataclass, field
from math import isfinite
import re
from typing import Any, Protocol, Sequence

from .client import Axon


_IDENTIFIER = re.compile(r"[A-Za-z_][A-Za-z0-9_.-]*\Z")
_RECORD_IDENTIFIER = re.compile(r"(?:[A-Za-z_][A-Za-z0-9_.-]*|[0-9]+)\Z")
_DISTANCES = frozenset({"cosine", "euclidean", "manhattan"})


class Embedder(Protocol):
    """Minimal async embedding interface required for text searches."""

    async def embed_query(self, query: str) -> Sequence[float]:
        """Return one embedding for a query."""


@dataclass(frozen=True)
class VectorRecord:
    """A document chunk and its precomputed embedding."""

    id: str
    content: str
    embedding: Sequence[float]
    metadata: dict[str, Any] = field(default_factory=dict)
    source_uri: str | None = None
    content_hash: str | None = None
    chunk_index: int | None = None

    def __post_init__(self) -> None:
        _validate_record_id(self.id)
        if not isinstance(self.content, str):
            raise TypeError("content must be a string")
        if not isinstance(self.metadata, dict):
            raise TypeError("metadata must be a dictionary")
        if self.chunk_index is not None and self.chunk_index < 0:
            raise ValueError("chunk_index must be non-negative")


@dataclass(frozen=True)
class SearchResult:
    """A vector match, ordered from nearest to farthest."""

    id: str
    content: str
    metadata: dict[str, Any]
    distance: float
    score: float
    source_uri: str | None = None
    content_hash: str | None = None
    chunk_index: int | None = None


class AxonBaseVectorStore:
    """An async AxonBase collection with an HNSW index over ``embedding``.

    This store intentionally does not implement metadata filtering. Use a normal
    AxonQL query when application-specific filtering is required.
    """

    def __init__(
        self,
        axon: Axon,
        collection: str,
        dimensions: int,
        distance: str = "cosine",
    ) -> None:
        _validate_identifier(collection, "collection")
        if dimensions <= 0:
            raise ValueError("dimensions must be positive")
        if distance not in _DISTANCES:
            raise ValueError("distance must be one of: cosine, euclidean, manhattan")
        self.axon = axon
        self.collection = collection
        self.dimensions = dimensions
        self.distance = distance
        self._index_name = f"{collection.replace('.', '_').replace('-', '_')}_embedding_hnsw"

    async def ensure_schema(self) -> None:
        """Create the schemaless collection and its HNSW embedding index."""
        await self.axon.query(f"DEFINE TABLE {self.collection} SCHEMALESS")
        await self.axon.query(
            f"DEFINE INDEX {self._index_name} ON TABLE {self.collection} "
            f"COLUMNS embedding HNSW DIMENSION {self.dimensions} DIST {self.distance}"
        )

    async def upsert(self, records: Sequence[VectorRecord]) -> None:
        """Write precomputed document embeddings using bound record content."""
        for record in records:
            self._validate_vector(record.embedding)
            content: dict[str, Any] = {
                "content": record.content,
                "metadata": record.metadata,
                "embedding": list(record.embedding),
            }
            if record.source_uri is not None:
                content["source_uri"] = record.source_uri
            if record.content_hash is not None:
                content["content_hash"] = record.content_hash
            if record.chunk_index is not None:
                content["chunk_index"] = record.chunk_index
            await self.axon.query(
                f"UPSERT {self.collection}:{record.id} CONTENT $record",
                {"record": content},
            )

    async def similarity_search_by_vector(
        self, vector: Sequence[float], limit: int = 5
    ) -> list[SearchResult]:
        """Return nearest HNSW matches for a vector.

        Results are ordered by the configured AxonBase distance function. A lower
        distance is better; ``score`` is ``1 - distance`` for convenience.
        """
        self._validate_vector(vector)
        if limit <= 0:
            raise ValueError("limit must be positive")
        distance_expression = f"vector::distance::{self.distance}(embedding, $vector)"
        sql = (
            "SELECT id, content, metadata, source_uri, content_hash, chunk_index, "
            f"{distance_expression} AS distance FROM {self.collection} "
            f"ORDER BY {distance_expression} LIMIT {limit}"
        )
        rows = _rows(await self.axon.query(sql, {"vector": list(vector)}))
        results = [_search_result(row, self.collection) for row in rows]
        return sorted(results, key=lambda result: result.distance)

    async def similarity_search(
        self, query: str, embedder: Embedder, limit: int = 5
    ) -> list[SearchResult]:
        """Embed a query asynchronously and return its nearest matches."""
        vector = await embedder.embed_query(query)
        return await self.similarity_search_by_vector(vector, limit)

    def _validate_vector(self, vector: Sequence[float]) -> None:
        if isinstance(vector, (str, bytes)) or len(vector) != self.dimensions:
            raise ValueError(f"expected {self.dimensions} dimensions, got {len(vector)}")
        if any(not isinstance(value, (int, float)) or not isfinite(value) for value in vector):
            raise ValueError("embedding values must be finite numbers")


def _validate_identifier(value: str, name: str) -> None:
    if not isinstance(value, str) or not _IDENTIFIER.fullmatch(value):
        raise ValueError(f"{name} must contain only letters, digits, underscores, hyphens, and dots")


def _validate_record_id(value: str) -> None:
    if not isinstance(value, str) or not _RECORD_IDENTIFIER.fullmatch(value):
        raise ValueError("record id must contain only letters, digits, underscores, hyphens, and dots")


def _rows(response: Any) -> list[dict[str, Any]]:
    """Normalize query result envelopes used by supported AxonBase servers."""
    if isinstance(response, dict):
        response = response.get("result", response.get("data", []))
    if not isinstance(response, list):
        raise TypeError("Axon query did not return a list of rows")
    return [row for row in response if isinstance(row, dict)]


def _search_result(row: dict[str, Any], collection: str) -> SearchResult:
    raw_id = str(row.get("id", ""))
    prefix = f"{collection}:"
    record_id = raw_id[len(prefix):] if raw_id.startswith(prefix) else raw_id
    distance = float(row.get("distance", 0.0))
    metadata = row.get("metadata")
    return SearchResult(
        id=record_id,
        content=str(row.get("content", "")),
        metadata=metadata if isinstance(metadata, dict) else {},
        distance=distance,
        score=1.0 - distance,
        source_uri=row.get("source_uri"),
        content_hash=row.get("content_hash"),
        chunk_index=row.get("chunk_index"),
    )
