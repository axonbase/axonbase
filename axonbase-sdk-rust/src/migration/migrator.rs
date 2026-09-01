use sha2::{Digest, Sha256};
use std::fs;
use std::path::{Path, PathBuf};

use crate::client::Axon;
use crate::errors::AxonError;

const DEFAULT_TABLE: &str = "_migration";
const CREATE_TABLE_SQL: &str = "DEFINE TABLE _migration SCHEMAFULL; DEFINE FIELD version ON TABLE _migration TYPE string; DEFINE FIELD name ON TABLE _migration TYPE string; DEFINE FIELD checksum ON TABLE _migration TYPE string; DEFINE FIELD applied_at ON TABLE _migration TYPE datetime DEFAULT time::now();";

#[derive(Debug, Clone)]
pub struct MigrationFile {
    pub version: String,
    pub name: String,
    pub path: PathBuf,
    pub checksum: String,
}

#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
pub struct MigrationRecord {
    pub version: String,
    pub name: String,
    pub checksum: String,
    pub applied_at: String,
}

pub type MigrationHook = Box<dyn Send + Sync + Fn(MigrationFile) -> Result<(), AxonError>>;

pub struct Migrator {
    axon: Axon,
    table: String,
    before_hooks: Vec<MigrationHook>,
    after_hooks: Vec<MigrationHook>,
}

impl Migrator {
    pub fn new(axon: Axon, table: Option<&str>) -> Self {
        Self {
            axon,
            table: table.unwrap_or(DEFAULT_TABLE).to_string(),
            before_hooks: Vec::new(),
            after_hooks: Vec::new(),
        }
    }

    pub fn on_before<F>(&mut self, hook: F)
    where
        F: Send + Sync + Fn(MigrationFile) -> Result<(), AxonError> + 'static,
    {
        self.before_hooks.push(Box::new(hook));
    }

    pub fn on_after<F>(&mut self, hook: F)
    where
        F: Send + Sync + Fn(MigrationFile) -> Result<(), AxonError> + 'static,
    {
        self.after_hooks.push(Box::new(hook));
    }

    pub async fn ensure_table(&self) -> Result<(), AxonError> {
        self.axon.query(CREATE_TABLE_SQL, None).await?;
        Ok(())
    }

    pub async fn applied(&self) -> Result<Vec<MigrationRecord>, AxonError> {
        let sql = format!(
            "SELECT version, name, checksum, applied_at FROM {} ORDER BY version ASC",
            self.table
        );
        let result = self.axon.query(&sql, None).await?;

        let rows: Vec<serde_json::Value> = match &result {
            serde_json::Value::Array(_) => serde_json::from_value(result)
                .map_err(|e| AxonError::Sdk(format!("failed to parse migration rows: {e}")))?,
            _ => Vec::new(),
        };

        let records = rows
            .into_iter()
            .filter_map(|row| {
                let obj = row.as_object()?;
                Some(MigrationRecord {
                    version: str_field(obj, "version"),
                    name: str_field(obj, "name"),
                    checksum: str_field(obj, "checksum"),
                    applied_at: dt_field(obj, "applied_at"),
                })
            })
            .collect();

        Ok(records)
    }

    pub fn load(&self, path: &Path) -> Result<Vec<MigrationFile>, AxonError> {
        let metadata = fs::metadata(path)
            .map_err(|e| AxonError::Sdk(format!("failed to stat {path:?}: {e}")))?;

        if metadata.is_file() {
            let ext = path.extension().and_then(|e| e.to_str()).unwrap_or("");
            if ext != "axql" {
                return Err(AxonError::Sdk(format!(
                    "not an .axql file: {}",
                    path.display()
                )));
            }
            return Ok(vec![parse_file(path)?]);
        }

        let mut entries: Vec<_> = fs::read_dir(path)
            .map_err(|e| AxonError::Sdk(format!("failed to read dir {path:?}: {e}")))?
            .filter_map(|entry| entry.ok())
            .filter(|e| e.path().extension().and_then(|s| s.to_str()) == Some("axql"))
            .collect();

        entries.sort_by_key(|e| e.file_name());

        let files: Result<Vec<_>, _> = entries.iter().map(|e| parse_file(&e.path())).collect();

        files
    }

    pub async fn status(
        &self,
        path: &Path,
    ) -> Result<(Vec<MigrationFile>, Vec<MigrationRecord>), AxonError> {
        let files = self.load(path)?;
        let applied = self.applied().await?;

        let applied_versions: std::collections::HashSet<String> =
            applied.iter().map(|r| r.version.clone()).collect();

        let pending: Vec<MigrationFile> = files
            .into_iter()
            .filter(|f| !applied_versions.contains(&f.version))
            .collect();

        Ok((pending, applied))
    }

    pub async fn up(&self, path: &Path) -> Result<Vec<MigrationRecord>, AxonError> {
        let (pending, _) = self.status(path).await?;
        let mut completed = Vec::new();

        for migration in &pending {
            for hook in &self.before_hooks {
                hook(migration.clone())?;
            }

            let sql = fs::read_to_string(&migration.path).map_err(|e| {
                AxonError::Sdk(format!("failed to read {}: {e}", migration.path.display()))
            })?;
            self.axon.query(&sql, None).await?;

            let create_sql = format!(
                "UPSERT {}:v_{} CONTENT {{ version: $v, name: $n, checksum: $c, applied_at: time::now() }}",
                self.table, migration.version
            );
            let vars = serde_json::json!({
                "v": migration.version,
                "n": migration.name,
                "c": migration.checksum,
            });
            let map: std::collections::HashMap<String, serde_json::Value> =
                serde_json::from_value(vars)
                    .map_err(|e| AxonError::Sdk(format!("vars serialization: {e}")))?;
            self.axon.query(&create_sql, Some(map)).await?;

            for hook in &self.after_hooks {
                hook(migration.clone())?;
            }

            completed.push(MigrationRecord {
                version: migration.version.clone(),
                name: migration.name.clone(),
                checksum: migration.checksum.clone(),
                applied_at: chrono::Utc::now().to_rfc3339(),
            });
        }

        Ok(completed)
    }
}

fn str_field(obj: &serde_json::Map<String, serde_json::Value>, key: &str) -> String {
    match obj.get(key) {
        Some(serde_json::Value::String(s)) => s.clone(),
        Some(other) => other.to_string().trim_matches('"').to_string(),
        None => String::new(),
    }
}

/// Extrai um campo datetime que o servidor devolve como objeto tipado
/// `{"$datetime":"..."}` e o normaliza para string ISO.
fn dt_field(obj: &serde_json::Map<String, serde_json::Value>, key: &str) -> String {
    match obj.get(key) {
        Some(serde_json::Value::String(s)) => s.clone(),
        Some(serde_json::Value::Object(m)) => m
            .get("$datetime")
            .and_then(|v| v.as_str())
            .map(|s| s.to_string())
            .unwrap_or_default(),
        Some(other) => other.to_string().trim_matches('"').to_string(),
        None => String::new(),
    }
}

fn parse_file(path: &Path) -> Result<MigrationFile, AxonError> {
    let file_name = path
        .file_name()
        .and_then(|n| n.to_str())
        .unwrap_or("")
        .to_string();

    let name = file_name
        .strip_suffix(".axql")
        .unwrap_or(&file_name)
        .to_string();

    let version = match name.find('_') {
        Some(idx) if idx > 0 => name[..idx].to_string(),
        _ => name.clone(),
    };

    let content = fs::read(path)
        .map_err(|e| AxonError::Sdk(format!("failed to read {}: {e}", path.display())))?;

    let checksum = format!("{:x}", Sha256::digest(&content));

    Ok(MigrationFile {
        version,
        name,
        path: path.to_path_buf(),
        checksum,
    })
}
