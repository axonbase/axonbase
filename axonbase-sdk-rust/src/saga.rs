use serde_json::Value;

use crate::client::Axon;
use crate::errors::AxonError;

/// Client-side helper for an AxonBase SAGA transaction.
pub struct SagaTransaction {
    axon: Axon,
    saga_name: String,
    correlation_id: String,
    begun: bool,
    finished: bool,
}

impl SagaTransaction {
    pub fn new(
        axon: Axon,
        saga_name: impl Into<String>,
        correlation_id: impl Into<String>,
    ) -> Self {
        Self {
            axon,
            saga_name: saga_name.into(),
            correlation_id: correlation_id.into(),
            begun: false,
            finished: false,
        }
    }

    pub fn saga_name(&self) -> &str {
        &self.saga_name
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
                "Saga already begun: {}",
                self.correlation_id
            )));
        }

        let result = self
            .axon
            .query(
                &format!(
                    "BEGIN SAGA {} WITH CORRELATION '{}'",
                    escape(&self.saga_name),
                    escape(&self.correlation_id)
                ),
                None,
            )
            .await?;
        if result.get("status").and_then(Value::as_str) == Some("RUNNING") {
            self.begun = true;
            return Ok(());
        }

        Err(AxonError::Sdk(format!("Failed to begin saga: {result}")))
    }

    pub async fn step(&self, axonql: &str) -> Result<Value, AxonError> {
        ensure_active(self.begun, self.finished)?;
        self.axon
            .let_var("saga_corr", Value::String(self.correlation_id.clone()))
            .await?;
        self.axon.query(axonql, None).await
    }

    pub async fn commit(&mut self) -> Result<(), AxonError> {
        ensure_active(self.begun, self.finished)?;
        let result = self
            .axon
            .query(
                &format!(
                    "COMMIT SAGA {} WITH CORRELATION '{}'",
                    escape(&self.saga_name),
                    escape(&self.correlation_id)
                ),
                None,
            )
            .await?;
        self.finished = true;
        if result.get("status").and_then(Value::as_str) == Some("COMMITTED") {
            return Ok(());
        }

        Err(AxonError::Sdk(format!("Saga commit failed: {result}")))
    }

    pub async fn rollback(&mut self) -> Result<(), AxonError> {
        if !self.begun || self.finished {
            return Ok(());
        }
        self.axon
            .query(
                &format!(
                    "CANCEL SAGA {} WITH CORRELATION '{}'",
                    escape(&self.saga_name),
                    escape(&self.correlation_id)
                ),
                None,
            )
            .await?;
        self.finished = true;
        Ok(())
    }

    pub async fn describe(&self) -> Result<Value, AxonError> {
        self.axon
            .query(
                &format!(
                    "SHOW SAGA TRANSACTION {} '{}'",
                    escape(&self.saga_name),
                    escape(&self.correlation_id)
                ),
                None,
            )
            .await
    }
}

fn ensure_active(begun: bool, finished: bool) -> Result<(), AxonError> {
    if !begun {
        return Err(AxonError::Sdk("Saga not begun".into()));
    }
    if finished {
        return Err(AxonError::Sdk("Saga already finished".into()));
    }
    Ok(())
}

fn escape(value: &str) -> String {
    value.replace('\\', "\\\\").replace('\'', "''")
}

#[cfg(test)]
mod tests {
    use super::{ensure_active, escape};

    #[test]
    fn escapes_saga_literals() {
        assert_eq!(escape("a\\b'c"), "a\\\\b''c");
    }

    #[test]
    fn validates_saga_lifecycle() {
        assert!(ensure_active(false, false).is_err());
        assert!(ensure_active(true, true).is_err());
        assert!(ensure_active(true, false).is_ok());
    }
}
