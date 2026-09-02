# Publicacao de releases

Este guia define os comandos de release das bibliotecas cliente e da imagem do banco. Todos os scripts recusam uma arvore Git com alteracoes nao commitadas.

## Convencoes

- Atualize a versao no manifesto da biblioteca antes do commit.
- Crie a tag indicada para a versao no commit de release.
- Os scripts de Python, Node.js, Rust, .NET e Ruby recebem a versao por `VERSION`.
- O Maven usa a versao do `pom.xml` raiz e exige a tag `v<versao>`.

## Maven Central

Publica o POM pai, `axonbase-common`, `axonbase-value`, `axonbase-sdk-java`, `axonbase-jdbc` e `axonbase-spring-data`. Os componentes do banco nao sao enviados ao Maven Central.

```bash
git tag v0.2.1
git push origin v0.2.1
bash scripts/publish-maven.sh
```

O bundle e validado no Central Portal. Confirme a publicacao em `https://central.sonatype.com/publishing/deployments`.

## Docker Hub

Publica a imagem do banco com as tags de versao e `latest` para `linux/amd64` e `linux/arm64`.

```bash
docker login
git tag v0.2.1
git push origin v0.2.1
bash scripts/publish-docker.sh
```

## Python

O Python Package Index (PyPI) recebe o pacote `axonbase-sdk`.

```bash
python3 -m pip install -e "./axonbase-sdk-python[test]" build twine
git tag axonbase-sdk-python/v0.1.0
git push origin axonbase-sdk-python/v0.1.0
VERSION=0.1.0 bash scripts/publish-python.sh
```

Configure antes uma credencial do PyPI para o `twine`, por exemplo em `~/.pypirc` ou por token de ambiente.

## Node.js

O npm (Node Package Manager) recebe `@axonbase/sdk`. A organizacao `@axonbase` precisa existir e a conta autenticada deve poder publicar pacotes publicos nesse escopo.

```bash
npm login
git tag axonbase-sdk-nodejs/v0.1.0
git push origin axonbase-sdk-nodejs/v0.1.0
VERSION=0.1.0 bash scripts/publish-nodejs.sh
```

## Rust

O crates.io recebe `axonbase-sdk`.

```bash
cargo login <TOKEN>
git tag axonbase-sdk-rust/v0.1.0
git push origin axonbase-sdk-rust/v0.1.0
VERSION=0.1.0 bash scripts/publish-rust.sh
```

## .NET

O NuGet recebe `AxonBase.Sdk`. O script executa os testes no alvo `dotnet-test` de `Dockerfile.sdk-tests`, gera o pacote no alvo `dotnet-pack` e envia o arquivo `.nupkg`. O .NET nao precisa estar instalado no host.

```bash
export NUGET_API_KEY=<TOKEN>
git tag axonbase-sdk-dotnet/v0.1.0
git push origin axonbase-sdk-dotnet/v0.1.0
VERSION=0.1.0 bash scripts/publish-dotnet.sh
```

## Ruby

O RubyGems recebe `axonbase-sdk`.

```bash
gem signin
git tag axonbase-sdk-ruby/v0.1.0
git push origin axonbase-sdk-ruby/v0.1.0
VERSION=0.1.0 bash scripts/publish-ruby.sh
```

## Go

O modulo e `github.com/axonbase/axonbase/axonbase-sdk-go`. O proxy Go publica por tag Git, sem um comando de upload separado. O script executa o alvo `go-test` de `Dockerfile.sdk-tests` e envia a tag ao remoto.

```bash
git tag axonbase-sdk-go/v0.1.0
VERSION=0.1.0 bash scripts/publish-go.sh
```

## PHP

O PHP (PHP: Hypertext Preprocessor) usa o pacote Composer `axonbase/sdk`. O Packagist exige que `composer.json` esteja na raiz de um repositorio publico dedicado, portanto este monorepo nao pode ser enviado diretamente ao Packagist.

1. Crie `github.com/axonbase/sdk-php` contendo os arquivos de `axonbase-sdk-php` na raiz.
2. Registre esse repositorio em `https://packagist.org/packages/submit`.
3. No repositorio dedicado, execute os testes e envie uma tag `v0.1.0`.

No monorepo, a validacao do pacote continua disponivel por:

```bash
docker build --target php-test --file Dockerfile.sdk-tests .
```
