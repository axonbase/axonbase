# Embeddings: busca por significado

Exemplo de busca por significado em português, sem escrever vetor nenhum. O banco chama um provedor HTTP de
embeddings (formato `/v1/embeddings`, o mesmo do Ollama, do llama.cpp, do vLLM e da OpenAI), grava o vetor de cada
registro e transforma o texto das buscas em vetor com o mesmo modelo.

## Como funciona

```sql
DEFINE EMBEDDING default PROVIDER http
  URL 'https://api.openai.com/v1/embeddings' MODEL 'text-embedding-3-small' DIMENSION 1536
  KEY ENV 'OPENAI_API_KEY';

DEFINE FIELD emb ON TABLE artigo TYPE vector<1536> EMBEDDING default FROM corpo;
CREATE INDEX artigo_vetor ON TABLE artigo COLUMNS emb HNSW DIMENSION 1536 DIST cosine;

CREATE artigo:1 CONTENT {titulo: 'Motoristas por perto', corpo: 'Como achar os motoristas num raio de dois quilômetros.'};
SELECT titulo FROM artigo WHERE emb @@ 'quem está perto de mim de carro' LIMIT 3;
```

- `DEFINE EMBEDDING` descreve o provedor. O nome `default` é o usado por `vector::embed('texto')` quando nenhum outro
  é indicado. Só existe o provedor `http`.
- A chave nunca vai no SQL nem no catálogo: `KEY ENV` guarda apenas o NOME da variável de ambiente, lida pelo servidor.
- `EMBEDDING default FROM corpo` faz o campo `emb` se preencher sozinho em `CREATE` e `UPDATE` (e só recalcula quando
  o `corpo` muda). Se o provedor estiver fora do ar, a escrita falha e nada é gravado. Um vetor informado na mão é
  mantido.
- `campo_vetorial @@ 'texto'` usa o embedding do próprio campo, então o modelo é sempre o da gravação. Sem
  `ORDER BY`, ordena pela distância e devolve os 10 mais próximos.
- `REEMBED TABLE artigo [FIELD emb] [DRY RUN]` regera os vetores. O `DRY RUN` só estima linhas, caracteres e tokens.

## Como usar

1. Suba o AxonBase com a variável da chave no ambiente (por exemplo `-e OPENAI_API_KEY=...`). Com a OpenAI, vale
   limitar os hosts com `-e AXON_EMBEDDING_ALLOWED_HOSTS=api.openai.com`.
2. Crie um banco só para o exemplo (por exemplo `app/embeddings`) e rode `seed.sql` com "Run all" no Workspace.
3. Rode as consultas de `queries.sql`. O `LET` só vale na consulta seguinte com "Run all".

Para privacidade total, use um modelo seu: aponte `URL` para um Ollama (`http://localhost:11434/v1/embeddings`, modelo
`bge-m3`, dimensão 1024). Dentro do Docker, o endereço do host é `host.docker.internal`.

## Cuidados

- Com provedor externo (OpenAI), o texto gravado e o das buscas saem do seu servidor, e o uso é cobrado por token.
  Antes de regerar uma tabela grande, rode `REEMBED ... DRY RUN`.
- O vetor de uma consulta só serve contra vetores do mesmo modelo e da mesma dimensão. Trocar o modelo exige trocar a
  dimensão do campo e do índice e regerar os vetores com `REEMBED`.
- Falhas 429 e 5xx são repetidas com espera crescente (respeitando `Retry-After`); outros erros do provedor chegam
  ao usuário como mensagem.
- Variáveis do servidor: `AXON_EMBEDDING_ALLOWED_HOSTS` (lista de hosts permitidos) e `AXON_EMBEDDING_CONCURRENCY`
  (chamadas simultâneas ao provedor, padrão 4).
