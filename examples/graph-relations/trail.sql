-- Como duas pessoas se conhecem: o caminho entre elas desenhado na aba Graph (a partir da versão 0.6.2).
-- Use um banco só para este exemplo (por exemplo app/grafo) e rode com "Run all" no Workspace.
-- Cenário: Ana segue Caio, que segue Dani. A Dani não segue a Ana e a Ana não segue a Dani.

CREATE pessoa:ana  CONTENT {nome: 'Ana'};
CREATE pessoa:bia  CONTENT {nome: 'Bia'};
CREATE pessoa:caio CONTENT {nome: 'Caio'};
CREATE pessoa:dani CONTENT {nome: 'Dani'};
RELATE pessoa:ana->segue->pessoa:caio;
RELATE pessoa:caio->segue->pessoa:dani;
-- a Bia segue a Ana, mas não faz parte do caminho entre Ana e Dani
RELATE pessoa:bia->segue->pessoa:ana;

-- 1. graph::trail(origem, destino, tipo, saltos, sentido): uma linha por pessoa do caminho, com uma coluna
--    "segue" apontando para a próxima. Clique em Graph no resultado: aparece Ana -> Caio -> Dani, só isso.
RETURN graph::trail(pessoa:ana, pessoa:dani, 'segue', 5);

-- 2. Os mesmos ids em lista (graph::path) e a resposta sim/não (graph::connected).
RETURN graph::path(pessoa:ana, pessoa:dani, 'segue', 5);
RETURN graph::connected(pessoa:ana, pessoa:dani, 'segue', 5);

-- 3. Sem caminho a resposta é uma lista vazia: ninguém segue a Ana "de volta" até a Dani no sentido das setas.
RETURN graph::trail(pessoa:dani, pessoa:ana, 'segue', 5);

-- 4. O quinto argumento muda o sentido: 'out' (padrão) segue as setas, 'in' anda contra elas e 'both' vale para os dois.
--    Aqui a Dani chega na Ana andando contra as setas; a Graph desenha as setas no sentido real (Ana -> Caio -> Dani).
RETURN graph::trail(pessoa:dani, pessoa:ana, 'segue', 5, 'in');

-- 5. Sem o tipo (null), o caminho pode usar qualquer aresta, e a coluna de cada linha leva o nome da aresta usada.
RELATE pessoa:dani->amigo_de->pessoa:bia;
RETURN graph::trail(pessoa:dani, pessoa:ana, null, 5, 'both');

-- 6. O caminho também vira linhas com os campos que você quiser (FROM aceita uma lista de ids).
LET $p = graph::path(pessoa:ana, pessoa:dani, 'segue', 5);
SELECT id, nome, array::intersect(array::flatten([->segue->pessoa]), $p) AS segue FROM $p;
