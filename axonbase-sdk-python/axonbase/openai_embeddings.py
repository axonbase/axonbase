"""Optional OpenAI embedding adapter.

``OpenAIEmbedder`` requires ``openai`` and an ``OPENAI_API_KEY`` available to
the OpenAI client through its standard configuration.
"""

from typing import Sequence


class OpenAIEmbedder:
    """Async embedding adapter using the optional OpenAI SDK."""

    def __init__(self, model: str = "text-embedding-3-small", client=None) -> None:
        if client is None:
            try:
                from openai import AsyncOpenAI
            except ImportError as error:
                raise ImportError(
                    "OpenAI embeddings require the optional dependency: "
                    "pip install 'axonbase-sdk[openai]'"
                ) from error
            client = AsyncOpenAI()
        self.client = client
        self.model = model

    async def embed_documents(self, texts: Sequence[str]) -> list[list[float]]:
        """Embed documents in one OpenAI API call."""
        if not texts:
            return []
        response = await self.client.embeddings.create(model=self.model, input=list(texts))
        return [list(item.embedding) for item in response.data]

    async def embed_query(self, query: str) -> list[float]:
        """Embed one query."""
        embeddings = await self.embed_documents([query])
        return embeddings[0]
