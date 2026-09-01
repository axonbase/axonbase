use serde_json::Value;

use crate::client::Axon;
use crate::errors::AxonError;

/// Joins an active SAGA while controlling only this client's local transaction.
pub struct SagaParticipantTransaction {
    axon: Axon,
    correlation_id: String,
    begun: bool,
    finished: bool,
}

impl SagaParticipantTransaction {
    pub fn new(axon: Axon, correlation_id: impl Into<String>) -> Self {
        Self {
            axon,
            correlation_id: correlation_id.into(),
            begun: false,
            finished: false,
        }
    }

    pub fn correlation_id(&self) -> &str {
        &self.correlation_id
    }

    pub fn is_begun(&self) -> bool {
        self.begun
    }

    pub fn is_finished(&self) -> bool {
        self.finished
    }

    pub async fn begin(&mut self) -> Result<(), AxonError> {
        if self.begun {
            return Err(AxonError::Sdk(format!(
                "Transaction already begun: {}",
                self.correlation_id
            )));
        }
        self.axon.begin().await?;
        if let Err(error) = self
            .axon
            .let_var("saga_corr", Value::String(self.correlation_id.clone()))
            .await
        {
            let _ = self.axon.cancel().await;
            return Err(error);
        }
        self.begun = true;
        Ok(())
    }

    pub async fn step(&self, axonql: &str) -> Result<Value, AxonError> {
        ensure_active(self.begun, self.finished)?;
        self.axon.query(axonql, None).await
    }

    pub async fn commit(&mut self) -> Result<(), AxonError> {
        ensure_active(self.begun, self.finished)?;
        self.axon.commit().await?;
        self.finished = true;
        Ok(())
    }

    pub async fn rollback(&mut self) -> Result<(), AxonError> {
        if !self.begun || self.finished {
            return Ok(());
        }
        self.axon.cancel().await?;
        self.finished = true;
        Ok(())
    }
}

fn ensure_active(begun: bool, finished: bool) -> Result<(), AxonError> {
    if !begun {
        return Err(AxonError::Sdk("Transaction not begun".into()));
    }
    if finished {
        return Err(AxonError::Sdk("Transaction already finished".into()));
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::ensure_active;

    #[test]
    fn validates_local_transaction_lifecycle() {
        assert!(ensure_active(false, false).is_err());
        assert!(ensure_active(true, true).is_err());
        assert!(ensure_active(true, false).is_ok());
    }
}
