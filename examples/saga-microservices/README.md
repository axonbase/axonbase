# Microsserviços SAGA

Este exemplo executa uma SAGA distribuída com três serviços FastAPI (FastAPI é o framework web Python usado pelos serviços): pagamento, nota fiscal e estoque. Cada serviço usa uma conexão com o orquestrador e outra com seu banco AxonBase local.

Os serviços repassam `X-Correlation-Id` entre as chamadas HTTP (Hypertext Transfer Protocol). O orquestrador propaga `BEGIN SAGA`, `COMMIT SAGA` e `CANCEL SAGA` aos participantes. Cada banco captura seu próprio estado anterior para compensação.

## Preparação

Inicie tudo com:

```bash
bash start_sqlalchemy_demo.sh
```

O script inicia o orquestrador em `8000`, os participantes em `8011`, `8012` e `8013`, e os serviços FastAPI em `9101`, `9102` e `9103`. Em seguida, cria as tabelas, o estoque inicial de 10 unidades, os DATABASE LINKs e a definição da SAGA. O exemplo reserva uma unidade, deixando o estoque em 9 após uma execução bem-sucedida.

## AxonQL direto

```bash
uvicorn payment_service:app --port 9001
uvicorn invoice_service:app --port 9002
uvicorn stock_service:app --port 9003
```

## SQLAlchemy

```bash
uvicorn payment_service_sqlalchemy:app --port 9101
uvicorn invoice_service_sqlalchemy:app --port 9102
uvicorn stock_service_sqlalchemy:app --port 9103
```

No exemplo SQLAlchemy, a opção `axonbase_saga_correlation_id` ativa a correlação na conexão local:

```python
with engine.begin() as connection:
    connection.execution_options(
        axonbase_saga_correlation_id=correlation_id
    ).execute(text("INSERT INTO payment (id, status) VALUES ('o1', 'PAID')"))
```

Inicie os bancos `payments`, `invoices` e `stock`, crie os DATABASE LINKs no banco `orchestrator` e defina `CREATE SAGA pedido WITH DATABASES 'payments', 'invoices', 'stock'` antes de chamar `POST /orders?order_id=o1`.

## Teste por curl

Sucesso: a ordem, a nota e a reserva de estoque são mantidas.

```bash
curl -i -X POST 'http://127.0.0.1:9101/orders?order_id=success_001'
```

Falha na nota: a nota é escrita, a falha é simulada e o orquestrador compensa pagamento e nota.

```bash
curl -i -X POST 'http://127.0.0.1:9101/orders?order_id=rollback_invoice_001&fail_at=invoice'
```

Falha no estoque: as três escritas ocorrem antes da falha e o orquestrador compensa as três.

```bash
curl -i -X POST 'http://127.0.0.1:9101/orders?order_id=rollback_stock_001&fail_at=stock'
```

Consulte os resultados no banco correto:

```bash
curl --data 'SELECT * FROM payment' -H 'Axon-Ns: test' -H 'Axon-Db: payments' http://127.0.0.1:8011/sql
curl --data 'SELECT * FROM invoice' -H 'Axon-Ns: test' -H 'Axon-Db: invoices' http://127.0.0.1:8012/sql
curl --data 'SELECT * FROM stock' -H 'Axon-Ns: test' -H 'Axon-Db: stock' http://127.0.0.1:8013/sql
```
