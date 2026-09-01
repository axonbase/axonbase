# Publicação dos SDKs AxonBase

Este documento descreve o passo a passo para publicar cada SDK no registry da respectiva linguagem. Antes de qualquer publicação, execute a suíte de validação completa.

## Pré-requisitos globais

1. Conta em cada registry com credenciais configuradas localmente.
2. Versionamento semântico: siga `0.x.0` até o primeiro release estável (`1.0.0`).
3. Toda publicação deve ser acompanhada de uma tag git `v<versão>` no commit correspondente.
4. Antes de publicar pela primeira vez, decida o **namespace definitivo**:
   - `com.axonbase` (Maven) / `@axonbase` (npm) / `axonbase` (PyPI, RubyGems, crates.io, Packagist)
   - O SDK Go precisa de um module path canônico (ex: `github.com/axonbase/sdk-go`)

---

## 1. Java (Maven Central)

**Problema**: o SDK depende de `axonbase-value`, `axonbase-common`, `axonbase-server` (test). Publicar o SDK exige que esses módulos também estejam no Maven Central.

**Registry**: [Maven Central](https://central.sonatype.com/) via `mvn deploy`

**Passos**:

1. Obter conta no Sonatype e configurar `~/.m2/settings.xml` com `server` e token.
2. Adicionar ao `pom.xml` raiz e de cada módulo:
   - `<distributionManagement>` com Sonatype OSSRH
   - `<licenses>`, `<scm>`, `<developers>`
   - `<plugins>`: `maven-source-plugin`, `maven-javadoc-plugin`, `maven-gpg-plugin`
3. Publicar os módulos base primeiro:
   - `axonbase-common`, `axonbase-value`, `axonbase-parser`, `axonbase-core`
4. Depois publicar:
   - `axonbase-sdk-java` (standalone, sem depender do `axonbase-server` de teste)
5. **Importante**: separar o SDK do módulo de teste que usa `axonbase-server`. O `SdkEndToEndTest` deve ser movido para fora ou dependências de test scoped removidas.

```bash
mvn clean deploy -pl axonbase-common,axonbase-value,axonbase-parser,axonbase-core -am -DskipTests
mvn clean deploy -pl axonbase-sdk-java -am -DskipTests
```

---

## 2. Python (PyPI)

**Registry**: [PyPI](https://pypi.org/) via `twine`

**Passos**:

1. Instalar ferramentas:
   ```bash
   pip install build twine
   ```
2. Gerar pacotes:
   ```bash
   cd axonbase-sdk-python
   rm -rf dist
   python -m build
   ```
3. Publicar:
   ```bash
   twine upload dist/*
   ```
   Para testar antes: `twine upload --repository-url https://test.pypi.org/legacy/ dist/*`

---

## 3. Node.js / TypeScript (npm)

**Registry**: [npm](https://www.npmjs.com/) via `npm publish`

**Observação**: o `name` em `package.json` é `@axonbase/sdk` (escopo privado). É necessário criar a organização `@axonbase` no npm ou usar um nome público.

**Passos**:

1. Criar organização `@axonbase` em `npmjs.com` ou mudar o nome para `axonbase-sdk`.
2. Fazer login:
   ```bash
   npm login --scope=@axonbase
   ```
3. Publicar:
   ```bash
   cd axonbase-sdk-nodejs
   npm run build
   npm publish --access public
   ```

---

## 4. Go (Go proxy)

**Registry**: [pkg.go.dev](https://pkg.go.dev/) — publicação automática via tag git

**Passos**:

1. Criar um repositório Go dedicado `github.com/axonbase/sdk-go` com o código do SDK. O module path deve corresponder ao repositório.
2. Alternativa curta: manter no monorepo e usar:
   ```
   module github.com/alvaro-brito-products/axonbase/axonbase-sdk-go
   ```
3. Publicar com tag:
   ```bash
   git tag axonbase-sdk-go/v0.1.0
   git push origin axonbase-sdk-go/v0.1.0
   ```
4. O proxy Go indexa automaticamente. Para forçar: acessar `https://sum.golang.org/lookup/github.com/alvaro-brito-products/axonbase/axonbase-sdk-go@v0.1.0`

---

## 5. Rust (crates.io)

**Registry**: [crates.io](https://crates.io/) via `cargo publish`

**Passos**:

1. Fazer login:
   ```bash
   cargo login <API_TOKEN>
   ```
2. Publicar:
   ```bash
   cd axonbase-sdk-rust
   cargo publish
   ```

> Nota: o `edition = "2024"` no Cargo.toml exige Rust nightly. Considere mudar para `2021` para compatibilidade com o toolchain estável.

---

## 6. .NET (NuGet)

**Registry**: [NuGet](https://www.nuget.org/) via `dotnet pack` + `nuget push`

**Passos**:

1. Adicionar metadados de pacote ao `AxonBaseSdk.csproj`:
   ```xml
   <PropertyGroup>
     <PackageId>AxonBase.Sdk</PackageId>
     <Version>0.1.0</Version>
     <Authors>AxonBase</Authors>
     <Description>AxonBase WebSocket JSON-RPC client for .NET</Description>
     <PackageLicenseExpression>MIT</PackageLicenseExpression>
     <RepositoryUrl>https://github.com/alvaro-brito-products/axonbase</RepositoryUrl>
   </PropertyGroup>
   ```
2. Empacotar:
   ```bash
   cd axonbase-sdk-dotnet/AxonBaseSdk
   dotnet pack -c Release
   ```
3. Publicar:
   ```bash
   dotnet nuget push bin/Release/AxonBase.Sdk.0.1.0.nupkg --api-key <API_KEY> --source https://api.nuget.org/v3/index.json
   ```

---

## 7. Ruby (RubyGems)

**Registry**: [RubyGems](https://rubygems.org/) via `gem push`

**Passos**:

1. Fazer login:
   ```bash
   gem signin
   ```
2. Construir a gem:
   ```bash
   cd axonbase-sdk-ruby
   gem build axonbase-sdk.gemspec
   ```
3. Publicar:
   ```bash
   gem push axonbase-sdk-0.1.0.gem
   ```

---

## 8. PHP (Packagist / Composer)

**Registry**: [Packagist](https://packagist.org/) integrado ao GitHub via webhook

**Passos**:

1. Registrar o pacote em `https://packagist.org/packages/axonbase/sdk` (requer credenciais e link com o repositório GitHub).
2. A cada tag, o Packagist atualiza automaticamente via GitHub webhook.
3. Publicar com tag:
   ```bash
   git tag v0.1.0
   git push origin v0.1.0
   ```

---

## CI/CD: GitHub Actions (recomendado)

Crie um workflow `publish.yml` que:
1. Escuta tags `v*`
2. Executa a suíte de testes de cada SDK
3. Publica cada SDK no registry correspondente

### Estrutura sugerida

```yaml
# .github/workflows/publish.yml
name: Publish SDKs
on:
  push:
    tags: ["v*"]
jobs:
  test-and-publish:
    strategy:
      matrix:
        sdk: [python, node, rust, ruby, php, dotnet, java, go]
    steps:
      - uses: actions/checkout@v4
      - name: Run SDK tests
        run: make test-${{ matrix.sdk }}
      - name: Publish ${{ matrix.sdk }}
        run: make publish-${{ matrix.sdk }}
```

### Makefile de exemplo

```makefile
test-python:
	cd axonbase-sdk-python && pip install -e ".[test]" && pytest

publish-python:
	cd axonbase-sdk-python && python -m build && twine upload dist/*

test-node:
	cd axonbase-sdk-nodejs && npm install && npm test

publish-node:
	cd axonbase-sdk-nodejs && npm run build && npm publish

test-rust:
	cd axonbase-sdk-rust && cargo test

publish-rust:
	cd axonbase-sdk-rust && cargo publish

test-ruby:
	cd axonbase-sdk-ruby && ruby -Ilib test/client_test.rb

publish-ruby:
	cd axonbase-sdk-ruby && gem build axonbase-sdk.gemspec && gem push *.gem

test-php:
	docker build --target php-test --file Dockerfile.sdk-tests .

publish-php:
	echo "PHP publica via tag git + Packagist webhook"

test-dotnet:
	cd axonbase-sdk-dotnet/AxonBaseSdk.Tests && dotnet run

publish-dotnet:
	cd axonbase-sdk-dotnet/AxonBaseSdk && dotnet pack -c Release && dotnet nuget push bin/Release/*.nupkg --api-key $$NUGET_API_KEY --source https://api.nuget.org/v3/index.json

test-go:
	cd axonbase-sdk-go && go test ./...

publish-go:
	echo "Go publica via tag git"

test-java:
	cd axonbase-sdk-java && mvn test

publish-java:
	cd axonbase-sdk-java && mvn deploy -DskipTests
```

---

## Ordem recomendada

1. **npm** (Node.js) — mais rápido, sem dependências externas
2. **crates.io** (Rust) — independente
3. **RubyGems** (Ruby) — independente
4. **Packagist** (PHP) — independente
5. **PyPI** (Python) — independente
6. **NuGet** (.NET) — precisa adicionar metadados no csproj
7. **Go proxy** — via tag git
8. **Maven Central** (Java) — último porque exige publicação dos módulos base primeiro

---

## Checklist pré-publicação

- [ ] `mvn test` passa
- [ ] Testes de cada SDK passam
- [ ] `git diff --check` limpo
- [ ] Versão atualizada em todos os manifests
- [ ] Tag git criada e enviada
- [ ] Licença MIT explícita em todos os manifests
- [ ] Metadados de pacote (descrição, autores, repo URL) em todos os manifests
- [ ] Nenhum segredo ou token hardcoded
- [ ] `.npmignore` / `.gitignore` corretos (excluir `tests/`, `node_modules/`)