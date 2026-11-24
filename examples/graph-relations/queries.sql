-- Consultas de grafo para rodar no Workspace depois de carregar seed.sql.
-- Quando uma coluna cita registros (ids), o resultado ganha a aba Graph: cada linha é um nó, cada id citado é uma seta
-- com o nome da coluna, e a coluna nome vira o rótulo do nó. Arraste os nós para organizar o desenho.

-- 1. Quem cada pessoa segue (-> segue as arestas que saem; o apelido vira o nome da coluna).
SELECT id, nome, ->segue->pessoa AS segue FROM pessoa;

-- 2. Quem segue a Ana (<- percorre as arestas que chegam).
SELECT id, <-segue<-pessoa AS seguidores FROM pessoa:ana;

-- 3. Vários tipos de aresta de uma vez: a vizinhança direta de uma pessoa.
SELECT id, nome,
       ->segue->pessoa AS segue,
       ->trabalha_em->empresa AS empresa,
       ->comprou->produto AS comprou,
       ->mora_em->cidade AS cidade
FROM pessoa:ana;

-- 4. A mesma vizinhança para todas as pessoas (rede inteira).
SELECT id, nome,
       ->segue->pessoa AS segue,
       ->trabalha_em->empresa AS empresa,
       ->comprou->produto AS comprou,
       ->mora_em->cidade AS cidade
FROM pessoa;

-- 5. Quem compra o mesmo produto: parte do produto e olha as arestas que chegam.
SELECT id, nome, <-comprou<-pessoa AS compradores FROM produto;

-- 6. Quem trabalha em cada empresa e o que a empresa fornece.
SELECT id, nome, <-trabalha_em<-pessoa AS equipe, ->fornece->produto AS fornece FROM empresa;

-- 7. Amigos de amigos: a travessia pode ser encadeada (dois saltos de segue).
SELECT id, nome, ->segue->pessoa->segue->pessoa AS amigos_de_amigos FROM pessoa:ana;

-- 8. Campo do registro alcançado: nomes em vez de ids.
SELECT id, ->comprou->produto.nome AS produtos, ->trabalha_em->empresa.nome AS empresa FROM pessoa;

-- 9. FETCH expande a coluna calculada pela travessia (cada produto vem com todos os campos).
SELECT id, ->comprou->produto AS produtos FROM pessoa:ana FETCH produtos;

-- 10. Remover arestas: uma aresta específica, ou todas as de saída de um tipo.
UNRELATE pessoa:ana->amigo_de->pessoa:edu;
UNRELATE pessoa:dani->segue;

-- 11. Apagar um registro apaga as arestas dele (de entrada e de saída).
DELETE pessoa:edu;
SELECT id, <-segue<-pessoa AS seguidores FROM pessoa:ana;

-- 12. Como duas pessoas se conhecem. Troque as duas primeiras linhas pelas pessoas que quer comparar.
-- O que cada uma tem em comum, em uma só linha de resultado: quem as liga, empresa, cidade e compras.
LET $x = pessoa:ana;
LET $y = pessoa:dani;
RETURN {
  segue_direto: $y IN array::flatten([$x->segue->pessoa]),
  amigo_direto: $y IN array::flatten([$x->amigo_de->pessoa]),
  seguem_os_dois: array::intersect(array::flatten([$x->segue->pessoa]), array::flatten([$y->segue->pessoa])),
  quem_liga_x_a_y: array::intersect(array::flatten([$x->segue->pessoa]), array::flatten([$y<-segue<-pessoa])),
  empresa_x: $x->trabalha_em->empresa,
  empresa_y: $y->trabalha_em->empresa,
  cidade_x: $x->mora_em->cidade,
  cidade_y: $y->mora_em->cidade,
  compras_em_comum: array::intersect(array::flatten([$x->comprou->produto]), array::flatten([$y->comprou->produto]))
};

-- 13. Caminho entre duas pessoas: graph::path(origem, destino, tipo_da_aresta, saltos_maximos, sentido) devolve a
-- sequência de registros pelo menor caminho (lista vazia se não houver). Tipo, limite e sentido são opcionais; o
-- sentido é 'out' (segue as setas, padrão), 'in' ou 'both'. graph::trail devolve o mesmo caminho em linhas, com uma
-- coluna com o nome da aresta apontando para o próximo nó: clique em Graph no resultado para ver o desenho.
RETURN graph::path(pessoa:ana, pessoa:dani, 'segue', 5);
RETURN graph::trail(pessoa:ana, pessoa:dani, 'segue', 5);
RETURN graph::trail(pessoa:dani, pessoa:ana, null, 5, 'both');
RETURN graph::connected(pessoa:ana, pessoa:dani);

-- 14. Quem está a 1, 2 ou 3 saltos (travessia encadeada) e se uma pessoa aparece no alcance da outra.
SELECT id,
       ->segue->pessoa AS um_salto,
       ->segue->pessoa->segue->pessoa AS dois_saltos,
       pessoa:dani IN ->segue->pessoa->segue->pessoa AS alcanca_a_dani_em_2
FROM pessoa:ana;

-- 15. Rede de uma pessoa: todas as ligações de entrada e saída de uma vez, para ver o desenho na aba Graph.
SELECT id, nome,
       ->segue->pessoa AS segue,
       <-segue<-pessoa AS seguidores,
       ->amigo_de->pessoa AS amigo,
       ->trabalha_em->empresa AS empresa,
       ->mora_em->cidade AS cidade
FROM pessoa;

-- Observação: com um único resultado a coluna volta como texto ("pessoa:caio"), não como lista.
