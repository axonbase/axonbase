-- Rede de exemplo para a página "Relations graph" e para consultas de grafo no Workspace.
-- Rode com "Run all" no Workspace (ou com POST /sql). Pode ser repetido: CREATE e RELATE regravam o mesmo registro e a
-- mesma aresta. A partir da versão 0.6.0, DELETE de um registro apaga também as arestas dele e UNRELATE remove uma aresta.

-- A partir da versão 0.5.1 o RELATE registra o tipo de aresta sozinho. As linhas DEFINE TABLE abaixo só são
-- necessárias em versões anteriores, e continuam valendo (declarar um tipo que já existe não muda nada).
DEFINE TABLE segue SCHEMALESS;
DEFINE TABLE trabalha_em SCHEMALESS;
DEFINE TABLE comprou SCHEMALESS;
DEFINE TABLE mora_em SCHEMALESS;
DEFINE TABLE fornece SCHEMALESS;

-- Limpeza de uma carga anterior. Desde a 0.6.0 o DELETE de um registro leva as arestas dele; em versões anteriores as
-- arestas ficavam soltas (o RELATE abaixo as regrava de qualquer forma).
DELETE pessoa; DELETE empresa; DELETE produto; DELETE cidade;

-- Nós
CREATE pessoa:ana CONTENT {nome: 'Ana', idade: 31};
CREATE pessoa:bia CONTENT {nome: 'Bia', idade: 27};
CREATE pessoa:caio CONTENT {nome: 'Caio', idade: 35};
CREATE pessoa:dani CONTENT {nome: 'Dani', idade: 29};
CREATE pessoa:edu CONTENT {nome: 'Edu', idade: 42};
CREATE empresa:acme CONTENT {nome: 'Acme Logística'};
CREATE empresa:byte CONTENT {nome: 'Byte Tecnologia'};
CREATE produto:notebook CONTENT {nome: 'Notebook', preco: 4200};
CREATE produto:mouse CONTENT {nome: 'Mouse', preco: 90};
CREATE produto:monitor CONTENT {nome: 'Monitor', preco: 1300};
CREATE cidade:sp CONTENT {nome: 'São Paulo'};
CREATE cidade:rj CONTENT {nome: 'Rio de Janeiro'};

-- Arestas: quem segue quem (há um ciclo: ana -> caio -> dani -> ana)
RELATE pessoa:ana->segue->pessoa:bia;
RELATE pessoa:ana->segue->pessoa:caio;
RELATE pessoa:bia->segue->pessoa:caio;
RELATE pessoa:caio->segue->pessoa:dani;
RELATE pessoa:dani->segue->pessoa:ana;
RELATE pessoa:edu->segue->pessoa:ana;
-- este tipo não foi declarado com DEFINE TABLE: a partir da 0.5.1 o próprio RELATE o registra
RELATE pessoa:ana->amigo_de->pessoa:edu;
-- onde trabalham, o que compraram, onde moram e quem fornece o quê
RELATE pessoa:ana->trabalha_em->empresa:acme;
RELATE pessoa:bia->trabalha_em->empresa:acme;
RELATE pessoa:caio->trabalha_em->empresa:byte;
RELATE pessoa:dani->trabalha_em->empresa:byte;
RELATE pessoa:ana->comprou->produto:notebook;
RELATE pessoa:ana->comprou->produto:mouse;
RELATE pessoa:bia->comprou->produto:monitor;
RELATE pessoa:caio->comprou->produto:notebook;
RELATE pessoa:dani->comprou->produto:mouse;
RELATE pessoa:ana->mora_em->cidade:sp;
RELATE pessoa:bia->mora_em->cidade:sp;
RELATE pessoa:caio->mora_em->cidade:rj;
RELATE pessoa:dani->mora_em->cidade:rj;
RELATE empresa:acme->fornece->produto:notebook;
RELATE empresa:byte->fornece->produto:monitor;
RELATE empresa:byte->fornece->produto:mouse;
