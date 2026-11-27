-- AxonPL: estrutura e dados do exemplo de delivery.
-- Rode num banco só para o exemplo (por exemplo app/axonpl_demo).

DEFINE TABLE produto SCHEMALESS;
DEFINE INDEX ux_produto_codigo ON TABLE produto COLUMNS codigo UNIQUE;
DEFINE TABLE cliente SCHEMALESS;
DEFINE TABLE pedido SCHEMALESS;

UPSERT produto:1 CONTENT {codigo: 'PIZ-001', nome: 'Pizza margherita', preco: 42.5, estoque: 20};
UPSERT produto:2 CONTENT {codigo: 'PIZ-002', nome: 'Pizza calabresa', preco: 46.0, estoque: 15};
UPSERT produto:3 CONTENT {codigo: 'BEB-001', nome: 'Refrigerante 2L', preco: 12.0, estoque: 40};
UPSERT produto:4 CONTENT {codigo: 'SOB-001', nome: 'Brownie', preco: 9.5, estoque: 3};

UPSERT cliente:1 CONTENT {nome: 'Ana', vip: true};
UPSERT cliente:2 CONTENT {nome: 'Bruno', vip: false};
