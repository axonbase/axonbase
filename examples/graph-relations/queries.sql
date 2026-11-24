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

-- Observação: com um único resultado a coluna volta como texto ("pessoa:caio"), não como lista.
