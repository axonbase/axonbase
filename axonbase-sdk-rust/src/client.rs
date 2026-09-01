use std::collections::HashMap;
use std::io::Cursor;
use std::path::Path;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, AtomicU32, Ordering};

use futures_util::{SinkExt, StreamExt};
use serde_json::Value;
use tokio::net::TcpStream;
use tokio::sync::{Mutex, RwLock, oneshot};
use tokio::time::{Duration, sleep, timeout};
use tokio_tungstenite::tungstenite::Message;
use tokio_tungstenite::{
    Connector, MaybeTlsStream, WebSocketStream, connect_async, connect_async_tls_with_config,
};

use crate::codec;
use crate::errors::AxonError;
use crate::protocol;

pub type LiveHandler = Arc<dyn Send + Sync + Fn(String, String, Value)>;

#[derive(Clone, Debug, Default)]
pub struct TlsOptions {
    ca_certificate: Option<Vec<u8>>,
    client_certificate: Option<Vec<u8>>,
    client_key: Option<Vec<u8>>,
}

impl TlsOptions {
    pub fn from_pem(
        ca_certificate: Option<Vec<u8>>,
        client_certificate: Option<Vec<u8>>,
        client_key: Option<Vec<u8>>,
    ) -> Self {
        Self {
            ca_certificate,
            client_certificate,
            client_key,
        }
    }

    pub fn from_pem_paths(
        ca_certificate: Option<&Path>,
        client_certificate: Option<&Path>,
        client_key: Option<&Path>,
    ) -> Result<Self, AxonError> {
        fn read(path: Option<&Path>) -> Result<Option<Vec<u8>>, AxonError> {
            path.map(std::fs::read)
                .transpose()
                .map_err(|error| AxonError::Sdk(format!("failed to read TLS PEM file: {error}")))
        }

        Ok(Self::from_pem(
            read(ca_certificate)?,
            read(client_certificate)?,
            read(client_key)?,
        ))
    }

    pub fn validate(&self) -> Result<(), AxonError> {
        self.client_config().map(|_| ())
    }

    fn client_config(&self) -> Result<rustls::ClientConfig, AxonError> {
        if self.client_certificate.is_some() != self.client_key.is_some() {
            return Err(AxonError::Sdk(
                "client certificate and private key must be configured together".into(),
            ));
        }

        let mut roots = rustls::RootCertStore::empty();
        roots.extend(webpki_roots::TLS_SERVER_ROOTS.iter().cloned());
        if let Some(ca_certificate) = &self.ca_certificate {
            let certificates = rustls_pemfile::certs(&mut Cursor::new(ca_certificate))
                .collect::<Result<Vec<_>, _>>()
                .map_err(|error| AxonError::Sdk(format!("invalid CA certificate PEM: {error}")))?;
            if certificates.is_empty() {
                return Err(AxonError::Sdk(
                    "CA certificate PEM contains no certificates".into(),
                ));
            }
            for certificate in certificates {
                roots
                    .add(certificate)
                    .map_err(|error| AxonError::Sdk(format!("invalid CA certificate: {error}")))?;
            }
        }

        let config = match (&self.client_certificate, &self.client_key) {
            (Some(certificate), Some(key)) => {
                let certificates = rustls_pemfile::certs(&mut Cursor::new(certificate))
                    .collect::<Result<Vec<_>, _>>()
                    .map_err(|error| {
                        AxonError::Sdk(format!("invalid client certificate PEM: {error}"))
                    })?;
                if certificates.is_empty() {
                    return Err(AxonError::Sdk(
                        "client certificate PEM contains no certificates".into(),
                    ));
                }
                let key = rustls_pemfile::private_key(&mut Cursor::new(key))
                    .map_err(|error| {
                        AxonError::Sdk(format!("invalid client private key PEM: {error}"))
                    })?
                    .ok_or_else(|| {
                        AxonError::Sdk("client private key PEM contains no key".into())
                    })?;
                rustls::ClientConfig::builder()
                    .with_root_certificates(roots)
                    .with_client_auth_cert(certificates, key)
                    .map_err(|error| {
                        AxonError::Sdk(format!("invalid client TLS identity: {error}"))
                    })?
            }
            (None, None) => rustls::ClientConfig::builder()
                .with_root_certificates(roots)
                .with_no_client_auth(),
            _ => unreachable!("TlsOptions::validate checked client authentication material"),
        };
        Ok(config)
    }
}

#[derive(Clone)]
pub struct AxonOptions {
    pub timeout: Duration,
    pub reconnect: bool,
    pub reconnect_interval: Duration,
    pub max_reconnect_attempts: u32,
    pub tls: Option<TlsOptions>,
}

impl Default for AxonOptions {
    fn default() -> Self {
        Self {
            timeout: Duration::from_secs(30),
            reconnect: true,
            reconnect_interval: Duration::from_secs(1),
            max_reconnect_attempts: 10,
            tls: None,
        }
    }
}

type WriteHalf =
    futures_util::stream::SplitSink<WebSocketStream<MaybeTlsStream<TcpStream>>, Message>;

struct Inner {
    writer: Mutex<Option<WriteHalf>>,
    next_id: AtomicU32,
    pending: Mutex<HashMap<u32, oneshot::Sender<protocol::RpcResponse>>>,
    live_handlers: RwLock<HashMap<String, LiveHandler>>,
    namespace: RwLock<String>,
    database: RwLock<String>,
    auth: RwLock<Option<String>>,
    hello: RwLock<Option<protocol::HelloPayload>>,
    closed: AtomicBool,
}

unsafe impl Send for Inner {}
unsafe impl Sync for Inner {}

pub struct Axon {
    url: RwLock<String>,
    options: AxonOptions,
    inner: Arc<Inner>,
}

impl Axon {
    pub async fn connect(url: &str, options: AxonOptions) -> Result<Self, AxonError> {
        let (ws, _) = Self::connect_websocket(url, options.tls.as_ref()).await?;

        let (write, mut read) = ws.split();
        let hello = Self::read_hello(&mut read, options.timeout).await?;

        let inner = Arc::new(Inner {
            writer: Mutex::new(Some(write)),
            next_id: AtomicU32::new(1),
            pending: Mutex::new(HashMap::new()),
            live_handlers: RwLock::new(HashMap::new()),
            namespace: RwLock::new(String::new()),
            database: RwLock::new(String::new()),
            auth: RwLock::new(None),
            hello: RwLock::new(Some(hello)),
            closed: AtomicBool::new(false),
        });

        let axon = Axon {
            url: RwLock::new(url.to_string()),
            options,
            inner: inner.clone(),
        };

        let inner_clone = axon.inner.clone();
        let opts = axon.options.clone();
        let url_clone = axon.url.read().await.clone();

        tokio::spawn(async move {
            Self::read_loop(inner_clone, read, opts, url_clone).await;
        });

        Ok(axon)
    }

    async fn connect_websocket(
        url: &str,
        tls: Option<&TlsOptions>,
    ) -> Result<
        (
            WebSocketStream<MaybeTlsStream<TcpStream>>,
            tokio_tungstenite::tungstenite::handshake::client::Response,
        ),
        AxonError,
    > {
        match tls {
            Some(tls) => connect_async_tls_with_config(
                url,
                None,
                false,
                Some(Connector::Rustls(Arc::new(tls.client_config()?))),
            )
            .await
            .map_err(|error| AxonError::Sdk(format!("failed to connect: {error}"))),
            None => connect_async(url)
                .await
                .map_err(|error| AxonError::Sdk(format!("failed to connect: {error}"))),
        }
    }

    async fn read_hello(
        read: &mut futures_util::stream::SplitStream<WebSocketStream<MaybeTlsStream<TcpStream>>>,
        wait: Duration,
    ) -> Result<protocol::HelloPayload, AxonError> {
        let message = timeout(wait, read.next())
            .await
            .map_err(|_| AxonError::Sdk("timed out waiting for server hello".into()))?
            .ok_or_else(|| AxonError::Sdk("connection closed before server hello".into()))?
            .map_err(|error| AxonError::Sdk(format!("failed to receive server hello: {error}")))?;
        let text = message
            .to_text()
            .map_err(|error| AxonError::Sdk(format!("invalid server hello frame: {error}")))?;
        let hello: protocol::HelloFrame = serde_json::from_str(text)
            .map_err(|error| AxonError::Sdk(format!("invalid server hello: {error}")))?;
        if hello.hello.protocol != protocol::PROTOCOL_VERSION {
            return Err(AxonError::ProtocolMismatch {
                message: "server protocol version is incompatible".into(),
                protocol: hello.hello.protocol,
                version: hello.hello.server,
            });
        }
        Ok(hello.hello)
    }

    async fn read_loop(
        inner: Arc<Inner>,
        mut read: impl StreamExt<Item = Result<Message, tokio_tungstenite::tungstenite::Error>> + Unpin,
        options: AxonOptions,
        _original_url: String,
    ) {
        while let Some(msg_result) = read.next().await {
            let msg = match msg_result {
                Ok(m) => m,
                Err(_) => break,
            };

            let text = match msg.to_text() {
                Ok(t) => t.to_string(),
                Err(_) => continue,
            };

            let parsed: Value = match serde_json::from_str(&text) {
                Ok(v) => v,
                Err(_) => continue,
            };

            if let Some(notif) = parsed.get("notification") {
                let id = notif
                    .get("id")
                    .and_then(|v| v.as_str())
                    .unwrap_or("")
                    .to_string();
                let action = notif
                    .get("action")
                    .and_then(|v| v.as_str())
                    .unwrap_or("")
                    .to_string();
                let result = notif.get("result").cloned().unwrap_or(Value::Null);

                let handlers = inner.live_handlers.read().await;
                if let Some(handler) = handlers.get(&id) {
                    handler(id, action, result);
                }
                continue;
            }

            if let Some(id_val) = parsed.get("id") {
                if let Some(id) = id_val.as_u64() {
                    let id_u32 = id as u32;
                    let mut pending = inner.pending.lock().await;
                    if let Some(sender) = pending.remove(&id_u32) {
                        let resp: protocol::RpcResponse =
                            serde_json::from_value(parsed).unwrap_or(protocol::RpcResponse {
                                id: Some(id_u32),
                                result: None,
                                error: Some(protocol::RpcError {
                                    code: -32700,
                                    message: "falha ao parsear resposta".into(),
                                    kind: None,
                                    leader: None,
                                    leader_address: None,
                                    protocol: None,
                                    version: None,
                                }),
                            });
                        let _ = sender.send(resp);
                    }
                }
            }
        }

        inner.closed.store(true, Ordering::SeqCst);
        let mut pending = inner.pending.lock().await;
        pending.clear();

        if options.reconnect {
            sleep(options.reconnect_interval).await;
        }
    }

    async fn call_raw(
        &self,
        method: &str,
        params: Vec<Value>,
    ) -> Result<protocol::RpcResponse, AxonError> {
        let id = self.inner.next_id.fetch_add(1, Ordering::SeqCst);

        let encoded_params: Vec<Value> = params.into_iter().map(codec::encode_typed).collect();
        let req = protocol::RpcRequest {
            id,
            method: method.to_string(),
            params: Some(encoded_params),
            version: Some(protocol::PROTOCOL_VERSION),
        };

        let json = serde_json::to_string(&req)
            .map_err(|e| AxonError::Sdk(format!("serialização falhou: {}", e)))?;

        let (tx, rx) = oneshot::channel();
        {
            let mut pending = self.inner.pending.lock().await;
            pending.insert(id, tx);
        }

        {
            let mut writer = self.inner.writer.lock().await;
            if let Some(w) = &mut *writer {
                w.send(Message::Text(json.into()))
                    .await
                    .map_err(|e| AxonError::Sdk(format!("envio falhou: {}", e)))?;
            } else {
                return Err(AxonError::Sdk("conexão não aberta".into()));
            }
        }

        let dur = self.options.timeout;
        let resp = timeout(dur, rx)
            .await
            .map_err(|_| AxonError::Sdk(format!("timeout em {}", method)))?
            .map_err(|_| AxonError::Sdk("canal fechado".into()))?;

        Ok(resp)
    }

    async fn call(&self, method: &str, params: Vec<Value>) -> Result<Value, AxonError> {
        let max_retries = if self.options.reconnect { 3 } else { 1 };

        for attempt in 0..max_retries {
            match self.call_raw(method, params.clone()).await {
                Ok(resp) => {
                    if let Some(err) = resp.error {
                        let axon_err = AxonError::from(err);
                        if let AxonError::NotLeader { leader_address, .. } = &axon_err {
                            if !leader_address.is_empty() && attempt < max_retries - 1 {
                                let scheme = if self.url.read().await.starts_with("wss://") {
                                    "wss://"
                                } else {
                                    "ws://"
                                };
                                let new_url = format!("{}{}/rpc/ws", scheme, leader_address);
                                let _ = self.reconnect_to(&new_url).await;
                                continue;
                            }
                        }
                        return Err(axon_err);
                    }
                    return Ok(resp.result.unwrap_or(Value::Null));
                }
                Err(e) => {
                    if attempt < max_retries - 1 && self.options.reconnect {
                        sleep(self.options.reconnect_interval).await;
                        let url = self.url.read().await.clone();
                        let _ = self.reconnect_to(&url).await;
                        continue;
                    }
                    return Err(e);
                }
            }
        }

        Err(AxonError::Sdk("falha após tentativas de reconexão".into()))
    }

    async fn reconnect_to(&self, new_url: &str) -> Result<(), AxonError> {
        let (ws, _) = Self::connect_websocket(new_url, self.options.tls.as_ref()).await?;

        let (write, mut read) = ws.split();
        let hello = Self::read_hello(&mut read, self.options.timeout).await?;

        *self.inner.hello.write().await = Some(hello);
        self.inner.closed.store(false, Ordering::SeqCst);

        {
            let mut writer = self.inner.writer.lock().await;
            *writer = Some(write);
        }

        let ns = self.inner.namespace.read().await.clone();
        let db = self.inner.database.read().await.clone();
        let auth = self.inner.auth.read().await.clone();
        if !ns.is_empty() {
            let _ = self
                .call_raw("use", vec![Value::String(ns), Value::String(db)])
                .await;
        }
        if let Some(token) = auth {
            let _ = self
                .call_raw("authenticate", vec![Value::String(token)])
                .await;
        }

        let inner = self.inner.clone();
        let opts = self.options.clone();
        let url = new_url.to_string();
        tokio::spawn(async move {
            Self::read_loop(inner, read, opts, url).await;
        });

        *self.url.write().await = new_url.to_string();
        Ok(())
    }

    pub async fn ping(&self) -> Result<bool, AxonError> {
        let v = self.call("ping", vec![]).await?;
        Ok(v.as_bool().unwrap_or(false))
    }

    pub async fn use_ns(&self, ns: &str, db: &str) -> Result<HashMap<String, String>, AxonError> {
        let v = self
            .call(
                "use",
                vec![Value::String(ns.into()), Value::String(db.into())],
            )
            .await?;
        let map = v
            .as_object()
            .map(|o| {
                o.iter()
                    .map(|(k, v)| (k.clone(), v.as_str().unwrap_or("").to_string()))
                    .collect()
            })
            .unwrap_or_default();
        *self.inner.namespace.write().await = ns.to_string();
        *self.inner.database.write().await = db.to_string();
        Ok(map)
    }

    pub async fn query(
        &self,
        sql: &str,
        vars: Option<HashMap<String, Value>>,
    ) -> Result<Value, AxonError> {
        match vars {
            Some(v) => {
                let map = serde_json::to_value(v)
                    .map_err(|e| AxonError::Sdk(format!("vars inválidos: {}", e)))?;
                self.call("query", vec![Value::String(sql.into()), map])
                    .await
            }
            None => self.call("query", vec![Value::String(sql.into())]).await,
        }
    }

    pub async fn signin(
        &self,
        user: &str,
        pass: &str,
        access: Option<&str>,
    ) -> Result<String, AxonError> {
        let mut cred = serde_json::Map::new();
        cred.insert("user".into(), Value::String(user.into()));
        cred.insert("pass".into(), Value::String(pass.into()));
        if let Some(a) = access {
            cred.insert("access".into(), Value::String(a.into()));
        }
        let v = self.call("signin", vec![Value::Object(cred)]).await?;
        let token = v.as_str().unwrap_or("").to_string();
        *self.inner.auth.write().await = Some(token.clone());
        Ok(token)
    }

    pub async fn authenticate(&self, token: &str) -> Result<(), AxonError> {
        self.call("authenticate", vec![Value::String(token.into())])
            .await?;
        *self.inner.auth.write().await = Some(token.to_string());
        Ok(())
    }

    pub async fn signup(&self, user: &str, pass: &str) -> Result<String, AxonError> {
        let mut cred = serde_json::Map::new();
        cred.insert("user".into(), Value::String(user.into()));
        cred.insert("pass".into(), Value::String(pass.into()));
        let v = self.call("signup", vec![Value::Object(cred)]).await?;
        let token = v.as_str().unwrap_or("").to_string();
        *self.inner.auth.write().await = Some(token.clone());
        Ok(token)
    }

    pub async fn invalidate(&self) -> Result<(), AxonError> {
        self.call("invalidate", vec![]).await?;
        *self.inner.auth.write().await = None;
        Ok(())
    }

    pub async fn certificate_begin(
        &self,
        store: &str,
    ) -> Result<protocol::CertificateChallenge, AxonError> {
        let result = self
            .call(
                "certificate.begin",
                vec![serde_json::json!({ "store": store })],
            )
            .await?;
        serde_json::from_value(result)
            .map_err(|error| AxonError::Sdk(format!("invalid certificate.begin response: {error}")))
    }

    pub async fn certificate_complete(
        &self,
        completion: protocol::CertificateCompletion,
    ) -> Result<String, AxonError> {
        let params = serde_json::to_value(completion).map_err(|error| {
            AxonError::Sdk(format!(
                "failed to serialize certificate completion: {error}"
            ))
        })?;
        let result = self.call("certificate.complete", vec![params]).await?;
        result
            .as_str()
            .map(str::to_owned)
            .ok_or_else(|| AxonError::Sdk("invalid certificate.complete response".into()))
    }

    pub async fn version(&self) -> Result<String, AxonError> {
        let v = self.call("version", vec![]).await?;
        Ok(v.as_str().unwrap_or("").to_string())
    }

    pub async fn select(&self, what: &str) -> Result<Value, AxonError> {
        self.call("select", vec![Value::String(what.into())]).await
    }

    pub async fn create(&self, what: &str, data: Value) -> Result<Value, AxonError> {
        self.call("create", vec![Value::String(what.into()), data])
            .await
    }

    pub async fn update(&self, what: &str, data: Value) -> Result<Value, AxonError> {
        self.call("update", vec![Value::String(what.into()), data])
            .await
    }

    pub async fn upsert(&self, what: &str, data: Value) -> Result<Value, AxonError> {
        self.call("upsert", vec![Value::String(what.into()), data])
            .await
    }

    pub async fn delete(&self, what: &str) -> Result<Value, AxonError> {
        self.call("delete", vec![Value::String(what.into())]).await
    }

    pub async fn insert(&self, table: &str, data: Value) -> Result<Value, AxonError> {
        self.call("insert", vec![Value::String(table.into()), data])
            .await
    }

    pub async fn relate(
        &self,
        from: &str,
        kind: &str,
        to: &str,
        data: Option<Value>,
    ) -> Result<Value, AxonError> {
        self.call(
            "relate",
            vec![
                Value::String(from.into()),
                Value::String(kind.into()),
                Value::String(to.into()),
                data.unwrap_or(Value::Object(serde_json::Map::new())),
            ],
        )
        .await
    }

    pub async fn let_var(&self, name: &str, value: Value) -> Result<(), AxonError> {
        let clean = name.strip_prefix('$').unwrap_or(name);
        self.call_raw("let", vec![Value::String(clean.into()), value])
            .await?;
        Ok(())
    }

    pub async fn unset(&self, name: &str) -> Result<(), AxonError> {
        let clean = name.strip_prefix('$').unwrap_or(name);
        self.call_raw("unset", vec![Value::String(clean.into())])
            .await?;
        Ok(())
    }

    pub async fn begin(&self) -> Result<(), AxonError> {
        self.call("begin", vec![]).await?;
        Ok(())
    }

    pub async fn commit(&self) -> Result<(), AxonError> {
        self.call("commit", vec![]).await?;
        Ok(())
    }

    pub async fn cancel(&self) -> Result<(), AxonError> {
        self.call("cancel", vec![]).await?;
        Ok(())
    }

    pub async fn kv_get(&self, ns: &str, db: &str, key: &str) -> Result<Value, AxonError> {
        self.call(
            "kv_get",
            vec![
                Value::String(ns.into()),
                Value::String(db.into()),
                Value::String(key.into()),
            ],
        )
        .await
    }

    pub async fn kv_set(
        &self,
        ns: &str,
        db: &str,
        key: &str,
        value: Value,
        ttl: Option<u32>,
    ) -> Result<Value, AxonError> {
        let ttl_val = ttl.map(|t| Value::Number(t.into())).unwrap_or(Value::Null);
        self.call(
            "kv_set",
            vec![
                Value::String(ns.into()),
                Value::String(db.into()),
                Value::String(key.into()),
                value,
                ttl_val,
            ],
        )
        .await
    }

    pub async fn kv_del(&self, ns: &str, db: &str, key: &str) -> Result<bool, AxonError> {
        let v = self
            .call(
                "kv_del",
                vec![
                    Value::String(ns.into()),
                    Value::String(db.into()),
                    Value::String(key.into()),
                ],
            )
            .await?;
        Ok(v.as_bool().unwrap_or(false))
    }

    pub async fn kv_scan(
        &self,
        ns: &str,
        db: &str,
        prefix: &str,
    ) -> Result<Vec<protocol::KvEntry>, AxonError> {
        let v = self
            .call(
                "kv_scan",
                vec![
                    Value::String(ns.into()),
                    Value::String(db.into()),
                    Value::String(prefix.into()),
                ],
            )
            .await?;
        let entries: Vec<protocol::KvEntry> = serde_json::from_value(v)
            .map_err(|e| AxonError::Sdk(format!("falha ao parsear kv_scan: {}", e)))?;
        Ok(entries)
    }

    pub async fn live(
        &self,
        table: &str,
        handler: LiveHandler,
        diff: bool,
    ) -> Result<String, AxonError> {
        let v = self
            .call("live", vec![Value::String(table.into()), Value::Bool(diff)])
            .await?;
        let id = v.as_str().unwrap_or("").to_string();
        self.inner
            .live_handlers
            .write()
            .await
            .insert(id.clone(), handler);
        Ok(id)
    }

    pub async fn kill(&self, id: &str) -> Result<bool, AxonError> {
        let v = self.call("kill", vec![Value::String(id.into())]).await?;
        let ok = v.as_bool().unwrap_or(false);
        if ok {
            self.inner.live_handlers.write().await.remove(id);
        }
        Ok(ok)
    }

    pub async fn namespace(&self) -> String {
        self.inner.namespace.read().await.clone()
    }

    pub async fn database(&self) -> String {
        self.inner.database.read().await.clone()
    }

    pub async fn hello_info(&self) -> Option<protocol::HelloPayload> {
        self.inner.hello.read().await.clone()
    }

    pub fn is_connected(&self) -> bool {
        !self.inner.closed.load(Ordering::SeqCst)
    }

    pub async fn close(&self) {
        self.inner.closed.store(true, Ordering::SeqCst);
        self.inner.live_handlers.write().await.clear();
        let mut writer = self.inner.writer.lock().await;
        if let Some(mut w) = writer.take() {
            let _ = w.close().await;
        }
    }
}

unsafe impl Send for Axon {}
unsafe impl Sync for Axon {}
