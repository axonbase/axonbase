# AxonBase

Base de dados multimodelo escrita em Java 21+, inspirada na arquitetura do SurrealDB. O projeto combina modelos de documento, grafo, relacional e chave-valor em um só motor, com linguagem de consulta própria (AxonQL), servidor HTTP (Hypertext Transfer Protocol) e WebSocket, e conectores SDK (Software Development Kit) em várias linguagens.

## Módulos

| Módulo | Pacote raiz | Papel |
|---|---|---|
| `axonbase-common` | `com.axonbase.common` | Utilidades, erros, controle de tempo, observabilidade mínima |
| `axonbase-value` | `com.axonbase.value` | Modelo de valores (AxonValue), codec JSON (JavaScript Object Notation), ordem total |
| `axonbase-parser` | `com.axonbase.parser` | Gramática da AxonQL (recursive descent) e AST (abstract syntax tree) |
| `axonbase-core` | `com.axonbase.core` | Motor: armazenamento, catálogo, transações, executor, índices, funções |
| `axonbase-server` | `com.axonbase.server` | Servidor HTTP + WebSocket, CLI, JWT (JSON Web Token), autenticação |
| `axonbase-sdk-java` | `com.axonbase.sdk` | SDK Java de cliente, conector de referência |

## Compilar e testar

```bash
mvn install
mvn test
```

## Estado

Em desenvolvimento. Fases 0 a 5 concluídas: AxonQL, núcleo do motor, servidor (HTTP + WebSocket), SDK Java e documentação. Executa-se AxonQL (CREATE/INSERT/UPDATE/DELETE/SELECT/RELATE/DEFINE/INFO/IF) sobre armazenamento em memória ou arquivo; o servidor expõe HTTP (`/sql`, `/table/*`, `/signin`, `/health`, `/version`, `/rpc`) e WebSocket (`/rpc/ws`); o SDK `axonbase-sdk-java` oferece `connect`, `use`, `query`, `create`, `select`, `update` e `delete`. Consulte `USAGE.md` para o guia de uso e `CONNECTOR.md` para a especificação do protocolo dos conectores.