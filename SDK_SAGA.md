# SAGA nos SDKs

Este guia mostra os helpers de SAGA (Saga Pattern), o padrão de transação distribuída com compensação, disponíveis nos SDKs (Software Development Kits) do AxonBase.

## Python assíncrono

```python
from axonbase import Axon, SagaTransaction

async with SagaTransaction(axon, "pedido", correlation_id) as saga:
    await saga.step("UPDATE payment:o1 SET status = 'PAID'")
```

## Python com SQLAlchemy

```python
from axonbase.sqlalchemy import SagaExecuteTransaction

with SagaExecuteTransaction(orchestrator, payments, "pedido") as transaction:
    transaction.begin(correlation_id)
    transaction.add(Payment(id=order_id, status="PAID"))
    transaction.commit()
```

## Participante Python com SQLAlchemy

Um participante recebe a correlação do microsserviço anterior. Ele usa somente o engine local e não inicia, confirma nem cancela a SAGA global.

```python
with SagaExecuteTransaction(invoices, "pedido") as transaction:
    transaction.begin(correlation_id)
    transaction.add(Invoice(id=order_id, status="ISSUED"))
    transaction.commit()
```

## Java

```java
axon.inSaga("pedido", correlationId, () -> {
    axon.query("UPDATE payment:o1 SET status = 'PAID'");
});
```

## Spring Data

```java
axonSagaTemplate.execute("pedido", correlationId, () -> {
    paymentRepository.save(payment);
    return payment;
});
```

`AxonSagaTemplate` participa da SAGA existente e confirma ou desfaz somente a transação Spring Data local. O microsserviço coordenador confirma ou cancela a SAGA global.

## Node.js

```typescript
const saga = new SagaTransaction(axon, "pedido", correlationId);
await saga.begin();
await saga.step("UPDATE payment:o1 SET status = 'PAID'");
await saga.commit();
```

## Go

```go
saga := axonbase.NewSagaTransaction(axon, "pedido", correlationID)
if err := saga.Begin(); err != nil { return err }
if _, err := saga.Step("UPDATE payment:o1 SET status = 'PAID'"); err != nil {
    saga.Rollback()
    return err
}
return saga.Commit()
```

## Rust

```rust
let mut saga = SagaTransaction::new(&axon, "pedido", correlation_id);
saga.begin().await?;
saga.step("UPDATE payment:o1 SET status = 'PAID'").await?;
saga.commit().await?;
```

## .NET

```csharp
var saga = new SagaTransaction(client, "pedido", correlationId);
await saga.BeginAsync();
await saga.StepAsync("UPDATE payment:o1 SET status = 'PAID'");
await saga.CommitAsync();
```

Todos os helpers definem a correlação antes de cada step para que o servidor registre o estado anterior e possa compensar a operação em `CANCEL SAGA`.
