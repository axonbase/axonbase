-- Embeddings: busca por significado com o texto em português, sem escrever vetor nenhum.
-- Use um banco só para este exemplo (por exemplo app/embeddings) e rode com "Run all" no Workspace.
--
-- O banco chama um provedor HTTP de embeddings (formato /v1/embeddings). A chave nunca vai no SQL: o servidor lê a
-- variável de ambiente indicada em KEY ENV. Escolha UM dos dois provedores abaixo e deixe o outro comentado.

-- ===== Opção A: OpenAI (o texto gravado e o das buscas vão para a OpenAI, e custa por token) =====
-- Suba o AxonBase com OPENAI_API_KEY no ambiente (e, se quiser, AXON_EMBEDDING_ALLOWED_HOSTS=api.openai.com).
DEFINE EMBEDDING default PROVIDER http
  URL 'https://api.openai.com/v1/embeddings'
  MODEL 'text-embedding-3-small'
  DIMENSION 1536
  KEY ENV 'OPENAI_API_KEY'
  TIMEOUT 30;

-- ===== Opção B: Ollama local (privado: nada sai da sua máquina) =====
-- Dentro do Docker, use http://host.docker.internal:11434 no lugar de localhost. Antes: ollama pull bge-m3
-- DEFINE EMBEDDING default PROVIDER http
--   URL 'http://localhost:11434/v1/embeddings'
--   MODEL 'bge-m3'
--   DIMENSION 1024;
-- Ao trocar de provedor, troque também o 1536 de vector<1536> e de HNSW DIMENSION abaixo pela dimensão do modelo.

-- ===== Tabela: o campo emb se preenche sozinho a partir de corpo =====
DEFINE TABLE artigo SCHEMAFULL;
DEFINE FIELD titulo ON TABLE artigo TYPE string;
DEFINE FIELD corpo ON TABLE artigo TYPE string;
DEFINE FIELD emb ON TABLE artigo TYPE vector<1536> EMBEDDING default FROM corpo;
CREATE INDEX artigo_vetor ON TABLE artigo COLUMNS emb HNSW DIMENSION 1536 DIST cosine;

-- Cada CREATE manda o corpo ao provedor, grava o vetor e já o indexa.
CREATE artigo:1 CONTENT {titulo: 'Motoristas por perto', corpo: 'Como achar os motoristas num raio de dois quilômetros e mostrar todos no mapa.'};
CREATE artigo:2 CONTENT {titulo: 'Quem segue quem', corpo: 'Relações entre pessoas com RELATE e o caminho mais curto entre duas pessoas.'};
CREATE artigo:3 CONTENT {titulo: 'Agregações rápidas', corpo: 'Somas, médias e contagens lidas direto do índice columnar, sem carregar os documentos.'};
CREATE artigo:4 CONTENT {titulo: 'Busca por relevância', corpo: 'O ranking BM25 ordena os documentos pela relevância dos termos buscados.'};
CREATE artigo:5 CONTENT {titulo: 'Bolo de chocolate', corpo: 'Receita de bolo de chocolate com cobertura de brigadeiro.'};
