import abc
import importlib
import sys
import types

import pytest

from axonbase.vectorstore import AxonBaseVectorStore, VectorRecord


class FakeAxon:
    def __init__(self, results=None):
        self.queries = []
        self.results = results or []

    async def query(self, sql, variables=None):
        self.queries.append((sql, variables))
        return self.results.pop(0) if self.results else []


class FakeEmbedder:
    async def embed_query(self, query):
        return [1.0, 0.0]

    async def aembed_documents(self, texts):
        return [[1.0, 0.0] for _ in texts]


def reload_adapter(monkeypatch, name, modules):
    for module_name, module in modules.items():
        monkeypatch.setitem(sys.modules, module_name, module)
    sys.modules.pop(name, None)
    return importlib.import_module(name)


def agno_modules():
    class VectorDb(abc.ABC):
        def __init__(self, **kwargs):
            self.options = kwargs

        @abc.abstractmethod
        def create(self): pass
        @abc.abstractmethod
        async def async_create(self): pass
        @abc.abstractmethod
        def name_exists(self, name): pass
        @abc.abstractmethod
        def async_name_exists(self, name): pass
        @abc.abstractmethod
        def id_exists(self, id): pass
        @abc.abstractmethod
        def content_hash_exists(self, content_hash, user_id=None): pass
        @abc.abstractmethod
        def insert(self, content_hash, documents, filters=None, user_id=None): pass
        @abc.abstractmethod
        async def async_insert(self, content_hash, documents, filters=None, user_id=None): pass
        @abc.abstractmethod
        def upsert(self, content_hash, documents, filters=None, user_id=None): pass
        @abc.abstractmethod
        async def async_upsert(self, content_hash, documents, filters=None, user_id=None): pass
        @abc.abstractmethod
        def search(self, query, limit=5, filters=None, user_id=None): pass
        @abc.abstractmethod
        async def async_search(self, query, limit=5, filters=None, user_id=None): pass
        @abc.abstractmethod
        def drop(self): pass
        @abc.abstractmethod
        async def async_drop(self): pass
        @abc.abstractmethod
        def exists(self): pass
        @abc.abstractmethod
        async def async_exists(self): pass
        @abc.abstractmethod
        def delete(self): pass
        @abc.abstractmethod
        def delete_by_id(self, id): pass
        @abc.abstractmethod
        def delete_by_name(self, name): pass
        @abc.abstractmethod
        def delete_by_metadata(self, metadata): pass
        @abc.abstractmethod
        def delete_by_content_id(self, content_id, user_id=None): pass
        @abc.abstractmethod
        def get_supported_search_types(self): pass

    class Document:
        def __init__(self, **kwargs):
            self.__dict__.update(kwargs)

    return {
        "agno": types.ModuleType("agno"),
        "agno.vectordb": types.ModuleType("agno.vectordb"),
        "agno.vectordb.base": types.SimpleNamespace(VectorDb=VectorDb),
        "agno.knowledge": types.ModuleType("agno.knowledge"),
        "agno.knowledge.document": types.SimpleNamespace(Document=Document),
    }


@pytest.mark.asyncio
async def test_agno_adapter_is_concrete_and_supports_async_basics(monkeypatch):
    module = reload_adapter(monkeypatch, "axonbase.integrations.agno", agno_modules())
    axon = FakeAxon(results=[[], [], []])
    adapter = module.AxonBaseAgnoVectorDb(
        AxonBaseVectorStore(axon, "chunks", 2), FakeEmbedder(), name="chunks"
    )

    await adapter.async_create()
    await adapter.async_insert(
        "hash", [types.SimpleNamespace(content="first", meta_data={})], {"kind": "test"}
    )
    await adapter.async_upsert(
        "hash", [types.SimpleNamespace(id="1", content="second", meta_data={})]
    )
    assert await adapter.async_search("first") == []
    assert await adapter.async_delete_by_id("0") is True
    assert adapter.get_supported_search_types() == ["vector"]
    assert axon.queries[2][0] == "UPSERT chunks:0 CONTENT $record"
    assert axon.queries[2][1]["record"]["content_hash"] == "hash"
    assert axon.queries[3][0] == "UPSERT chunks:1 CONTENT $record"
    with pytest.raises(NotImplementedError, match="async-first"):
        adapter.create()


@pytest.mark.asyncio
async def test_langchain_adapter_is_concrete_with_fake_vectorstore(monkeypatch):
    class VectorStore(abc.ABC):
        @abc.abstractmethod
        def similarity_search(self, query, k=4, **kwargs): pass

        @classmethod
        @abc.abstractmethod
        def from_texts(cls, texts, embedding, metadatas=None, **kwargs): pass

    module = reload_adapter(monkeypatch, "axonbase.integrations.langchain", {
        "langchain_core": types.ModuleType("langchain_core"),
        "langchain_core.vectorstores": types.SimpleNamespace(VectorStore=VectorStore),
    })
    adapter = module.AxonBaseLangChainVectorStore(
        AxonBaseVectorStore(FakeAxon(), "chunks", 2), FakeEmbedder()
    )

    assert adapter.embeddings.__class__ is FakeEmbedder
    assert await adapter.aadd_documents([
        types.SimpleNamespace(id="0", page_content="first", metadata={})
    ]) == ["0"]
    assert adapter.store.axon.queries[0][0] == "UPSERT chunks:0 CONTENT $record"
    with pytest.raises(NotImplementedError, match="async-first"):
        adapter.similarity_search("query")
    with pytest.raises(NotImplementedError, match="Construct"):
        module.AxonBaseLangChainVectorStore.from_texts([], FakeEmbedder())


def test_numeric_vector_record_ids_are_valid():
    assert VectorRecord("0", "content", [1.0]).id == "0"
