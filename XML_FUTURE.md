# XML: recurso futuro

Este documento registra uma proposta futura. O AxonBase ainda não possui suporte
nativo a XML (Extensible Markup Language), XSD (XML Schema Definition), XPath
(XML Path Language), XMLDSig (XML Digital Signature) ou XAdES (XML Advanced
Electronic Signatures).

O XML pode ser guardado hoje apenas como texto ou bytes. Não há validação de
schema, consulta por tags, índice de caminhos, nem suporte JDBC (Java Database
Connectivity) a `SQLXML`.

## Objetivo

Permitir ingestão segura de documentos XML, validação por XSD versionado,
consulta seletiva por caminhos, validação de assinatura e geração de relatórios
sobre projeções tipadas, preservando o XML original para auditoria.

## Escopo proposto

### Tipo XML

Adicionar um tipo `xml` ao modelo de valores, persistido sem reformatar o
conteúdo original. O codec usará um envelope explícito:

```json
{"$xml":"<invoice/>"}
```

O XML original é necessário para auditoria e para preservar o contexto de uma
assinatura. O banco não deve armazenar uma árvore DOM (Document Object Model)
mutável como valor.

### Consultas seguras

A primeira versão expõe um subconjunto seguro de XPath por funções AxonQL:

```sql
SELECT xml::value(payload, "/invoice/customer/id") AS customer_id
FROM invoice;

SELECT * FROM invoice
WHERE xml::exists(payload, "/invoice/items/item[@sku='A-10']");
```

Funções iniciais:

- `xml::exists(xml, path)`
- `xml::value(xml, path)`
- `xml::values(xml, path)`
- `xml::count(xml, path)`

XPath completo não entra na primeira versão. Funções externas, acesso a rede,
acesso a arquivos e expressões de custo não limitado devem ser bloqueados.

### Schema Registry por banco

O registro de schemas terá escopo de `DATABASE`. Cada schema será imutável,
versionado e identificado por digest SHA-256 (Secure Hash Algorithm 256-bit).

```sql
DEFINE XML SCHEMA invoice VERSION "1.0.0" CONTENT '<xs:schema>...</xs:schema>';
DEFINE XML SCHEMA invoice VERSION "2.0.0" CONTENT '<xs:schema>...</xs:schema>';
```

O catálogo replicado será a fonte de verdade. O schema compilado fica somente
em cache de memória. Imports e includes de XSD não podem resolver URLs ou
arquivos arbitrários; uma versão futura poderá permitir apenas referências a
schemas já registrados, por versão e digest.

### Validação dinâmica por campo

O usuário definirá no campo XML qual schema e qual política de assinatura se
aplicam a `CREATE` e `UPDATE`.

```sql
DEFINE FIELD payload ON TABLE invoice TYPE xml
  XML SCHEMA invoice
    VERSION FROM ATTRIBUTE "/invoice/@schemaVersion"
    ON CREATE, UPDATE
  XML SIGNATURE invoice_signature
    ON CREATE, UPDATE;
```

A versão também pode ser fixa ou vir de outro campo do registro:

```sql
XML SCHEMA invoice VERSION "2.0.0" ON CREATE, UPDATE;
XML SCHEMA invoice VERSION FROM FIELD schema_version ON CREATE, UPDATE;
```

Ao gravar, o banco resolve e registra internamente schema, versão e digest
efetivos. Um documento antigo continua associado à versão usada na sua escrita,
mesmo que uma versão mais nova se torne ativa.

### Assinaturas XML

O recurso cobrirá XMLDSig enveloped e XAdES Baseline B. A primeira versão
aceitará uma assinatura, uma referência local por identificador, canonicalização
exclusiva, algoritmos SHA-256 ou superiores, RSA (Rivest-Shamir-Adleman) e ECDSA
(Elliptic Curve Digital Signature Algorithm).

Serão rejeitados SHA-1, HMAC (Hash-based Message Authentication Code), DSA
(Digital Signature Algorithm), referências externas, XSLT (Extensible Stylesheet
Language Transformations), transformações XPath arbitrárias, múltiplas
assinaturas e identificadores duplicados.

A confiança do assinante será validada por PKIX (Public Key Infrastructure
using X.509) contra um JKS (Java KeyStore) associado à política. O resultado
persistirá somente metadados de auditoria, como fingerprint, algoritmo, digest,
política e instante da validação.

XAdES-T, XAdES-LT e XAdES-LTA ficam para uma fase posterior. Esses perfis exigem
carimbo do tempo, evidências de revogação e regras de retenção adicionais.

### Índices XML

Índices devem ser criados somente para caminhos usados por filtros, relações ou
dimensões de relatório:

```sql
DEFINE XML INDEX invoice_sku
ON TABLE invoice
FIELD payload
PATH "/invoice/items/item/@sku"
TYPE string;
```

O índice deve ser usado somente para igualdade e existência com caminhos
determinísticos. A expressão XML continua sendo avaliada após a busca para
garantir a correção.

Não indexar todos os caminhos de um XML. Elementos repetidos, textos longos e
estruturas de alta cardinalidade causam crescimento excessivo de índices e
amplificação de escrita.

## Escala e relatórios

Com mais de 10 milhões de documentos, o XML bruto não pode estar no caminho de
relatórios. O RocksDB é capaz de armazenar o volume, mas o executor atual
materializa documentos para filtros, ordenação e agregações. O índice columnar
atual também encontra identificadores e depois carrega documentos completos.

O desenho necessário separa evidência e consulta:

```text
XML bruto
  -> parser seguro
  -> validação XSD e assinatura
  -> extração tipada
  -> projeções de relatório e índices seletivos
  -> agregados materializados
```

Exemplo de projeções:

```text
invoice_document: XML bruto, versão do schema, assinatura e metadados
invoice_fact: emissor, cliente, status, data e total
invoice_line_fact: invoice_id, item, quantidade e preço
```

Relatórios devem consultar `invoice_fact` e `invoice_line_fact`, não executar
XPath sobre todos os XMLs. Isso evita parse repetido, pressão de heap e
agregações que crescem com o número de documentos.

Como referência de capacidade, 10 milhões de documentos com 1 KiB (kibibyte),
10 KiB e 100 KiB de XML ocupam aproximadamente 9,3 GiB (gibibyte), 93 GiB e
931 GiB somente em payload bruto. O planejamento precisa adicionar índices,
projeções, WAL (Write-Ahead Log), compactação, backups, réplicas e margem
operacional.

## Pré-requisitos de escala

Antes de prometer relatórios XML em escala, o mecanismo deve evoluir para:

- Iteradores paginados no backend, sem materializar todas as chaves de um scan.
- Operadores streaming para filtros e agregações.
- Agregação com limite de memória e spill em disco.
- `Top-N` para `ORDER BY ... LIMIT`.
- Leituras columnar index-only para agregações e ranges.
- Column families RocksDB separadas para documentos, projeções, índices,
  agregados, catálogo e versões.
- Métricas de cache, compactação, write stalls e amplificação de escrita.
- Pipeline de ingestão com backpressure e lotes limitados por bytes.
- Réplicas ou processos dedicados a relatórios, separados da ingestão.

Raft melhora disponibilidade e consistência, mas não reduz o custo de uma
consulta analítica sobre todos os dados. O particionamento deve seguir os
filtros predominantes, como tenant, emissor ou período.

## Segurança obrigatória

- Desabilitar DTD (Document Type Definition), entidades externas, XInclude e
  acesso a arquivo ou rede durante parse e validação.
- Limitar bytes, profundidade, nós, atributos, tamanho de texto, resultado XPath
  e tempo de execução.
- Impedir signature wrapping, IDs duplicados e referências externas.
- Separar validade criptográfica de confiança do certificado.
- Validar cadeia, período de validade, uso de chave, algoritmos e política de
  revogação de forma fail-closed.
- Nunca persistir senha de JKS, chave privada ou XML não validado em logs de
  auditoria.

## Fases futuras

1. Tipo XML, codec, literal AxonQL, persistência, backup e replicação.
2. Parser XML seguro, funções de caminho e validação de XML bem-formado.
3. Schema Registry XSD por banco e binding de campo.
4. Seleção de versão fixa, por campo e por atributo XML.
5. Índice de caminhos e projeções de relatório.
6. Evolução do backend e executor para cargas analíticas.
7. XMLDSig enveloped e XAdES Baseline B.
8. JDBC `SQLXML`, SDKs, documentação e benchmark de escala.
9. XAdES-T, XAdES-LT e XAdES-LTA, se houver requisito regulatório.

## Critérios de aceite

- Corpus representativo com pelo menos 10 milhões de documentos.
- Ingestão sustentada durante um ciclo completo de compactação.
- Métricas p50, p95 e p99 de parse, XSD, assinatura, extração e escrita.
- Relatórios sem parse de XML no caminho de consulta.
- Reconstrução idempotente de projeções a partir do XML bruto.
- Backup, recuperação e failover validados sob carga.
- Sem esgotamento de heap, write stalls prolongados ou crescimento de disco sem
  controle.
