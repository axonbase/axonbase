# AxonBase

Base de dados multimodelo escrita em Java 21+, inspirada na arquitetura do SurrealDB. O projeto combina modelos de documento, grafo, relacional e chave-valor em um só motor, com linguagem de consulta própria (AxonQL), servidor HTTP (Hypertext Transfer Protocol) e WebSocket, e conectores SDK (Software Development Kit) em várias linguagens.

## Módulos

| Módulo | Pacote raiz | Papel |
|---|---|---|
| `axonbase-common` | `com.axonbase.common` | Utilidades, erros, controle de tempo, observabilidade mínima |
| `axonbase-value` | `com.axonbase.value` | Modelo de valores (AxonValue), codec JSON (JavaScript Object Notation), ordem total |
| `axonbase-parser` | `com.axonbase.parser` | Gramática da AxonQL (recursive descent) e AST (abstract syntax tree) |
| `axonbase-core` | `com.axonbase.core` | Motor: armazenamento, catálogo, transações, executor, índices, funções |
| `axonbase-server` | `com.axonbase.server` | Servidor HTTP + WebSocket, CLI (Command-Line Interface), JWT (JSON Web Token), autenticação |
| `axonbase-sdk-java` | `com.axonbase.sdk` | SDK Java de cliente, conector de referência |
| `axonbase-sdk-php` | `AxonBase` | SDK PHP (PHP: Hypertext Preprocessor) de cliente WebSocket |
| `axonbase-sdk-ruby` | `AxonBase` | SDK Ruby de cliente WebSocket |

## Compilar e testar

```bash
mvn install
mvn test
```

## Estado

Em desenvolvimento. Fases 0 a 5 concluídas: AxonQL, núcleo do motor, servidor (HTTP + WebSocket), SDK Java e documentação. Executa-se AxonQL (CREATE/INSERT/UPDATE/DELETE/SELECT/RELATE/DEFINE/INFO/IF) sobre armazenamento em memória ou arquivo; o servidor expõe HTTP (`/sql`, `/table/*`, `/signin`, `/health`, `/version`, `/rpc`, `/graphql`, `/mcp/*`) e WebSocket (`/rpc/ws`). Os SDKs `axonbase-sdk-java`, `axonbase-sdk-php` e `axonbase-sdk-ruby` oferecem clientes WebSocket. Consulte `USAGE.md` para o guia de uso, `CONNECTOR.md` para a especificação do protocolo e os READMEs dos SDKs PHP e Ruby para instalação e autenticação.
