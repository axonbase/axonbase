use axonbase_sdk::{
    Axon, AxonOptions, CertificateChallenge, CertificateCompletion, ERR_AUTH, ERR_NO_QUORUM,
    ERR_NOT_LEADER, ERR_TXN_CONFLICT, METHODS, PROTOCOL_VERSION, TlsOptions, codec,
    errors::AxonError, protocol,
};

#[tokio::test]
async fn test_protocol_constants() {
    assert_eq!(PROTOCOL_VERSION, 1);
    assert!(METHODS.contains(&"ping"));
    assert!(METHODS.contains(&"query"));
    assert!(METHODS.contains(&"live"));
    assert!(METHODS.contains(&"kv_get"));
    assert!(METHODS.contains(&"certificate.begin"));
    assert!(METHODS.contains(&"certificate.complete"));
    assert_eq!(ERR_NOT_LEADER, -32010);
    assert_eq!(ERR_NO_QUORUM, -32011);
    assert_eq!(ERR_AUTH, -32002);
    assert_eq!(ERR_TXN_CONFLICT, -32009);
}

#[test]
fn test_certificate_challenge_deserialize() {
    let challenge: CertificateChallenge = serde_json::from_str(
        r#"{"id":"challenge-1","challenge":"nonce","expires_at":"2026-01-01T00:00:00Z"}"#,
    )
    .unwrap();

    assert_eq!(challenge.id, "challenge-1");
    assert_eq!(challenge.challenge, "nonce");
    assert_eq!(challenge.expires_at, "2026-01-01T00:00:00Z");
}

#[test]
fn test_certificate_completion_serializes_server_contract() {
    let completion = CertificateCompletion {
        id: "challenge-1".into(),
        challenge: "nonce".into(),
        store: "government-ca".into(),
        user: "12345678901".into(),
        chain: vec!["MIIB...".into()],
        signature: "MEUC...".into(),
    };

    assert_eq!(
        serde_json::to_value(completion).unwrap(),
        serde_json::json!({
            "id": "challenge-1",
            "challenge": "nonce",
            "store": "government-ca",
            "user": "12345678901",
            "chain": ["MIIB..."],
            "signature": "MEUC..."
        })
    );
}

#[test]
fn test_tls_options_reject_incomplete_client_auth() {
    let tls = TlsOptions::from_pem(None, Some(b"certificate".to_vec()), None);

    assert!(tls.validate().is_err());
}

#[test]
fn test_tls_options_accepts_custom_ca_without_client_auth() {
    let tls = TlsOptions::from_pem(
        Some(include_bytes!("../../certs/ca/dev-ca.crt").to_vec()),
        None,
        None,
    );

    assert!(tls.validate().is_ok());
}

#[test]
fn test_tls_options_rejects_invalid_ca_pem() {
    let tls = TlsOptions::from_pem(Some(b"not a PEM certificate".to_vec()), None, None);

    assert!(tls.validate().is_err());
}

#[tokio::test]
async fn test_hello_frame_deserialize() {
    let json =
        r#"{"hello": {"protocol": 1, "server": "0.1.0-SNAPSHOT", "methods": ["ping", "query"]}}"#;
    let hello: protocol::HelloFrame = serde_json::from_str(json).unwrap();
    assert_eq!(hello.hello.protocol, 1);
    assert_eq!(hello.hello.server, "0.1.0-SNAPSHOT");
    assert!(hello.hello.methods.contains(&"ping".to_string()));
}

#[tokio::test]
async fn test_rpc_request_serialize() {
    let req = protocol::RpcRequest {
        id: 1,
        method: "ping".into(),
        params: Some(vec![]),
        version: Some(PROTOCOL_VERSION),
    };
    let json = serde_json::to_string(&req).unwrap();
    assert!(json.contains("\"method\":\"ping\""));
    assert!(json.contains("\"id\":1"));
}

#[tokio::test]
async fn test_rpc_response_with_error() {
    let json = r#"{"id": 1, "error": {"code": -32010, "message": "not leader", "kind": "NOT_LEADER", "leader": "n1", "leader_address": "10.0.0.1:8000"}}"#;
    let resp: protocol::RpcResponse = serde_json::from_str(json).unwrap();
    assert!(resp.error.is_some());
    let err = resp.error.unwrap();
    assert_eq!(err.code, -32010);
    assert_eq!(err.leader.unwrap(), "n1");
}

#[tokio::test]
async fn test_notification_frame() {
    let json =
        r#"{"notification": {"id": "abc123", "action": "CREATE", "result": {"name": "Ana"}}}"#;
    let notif: protocol::NotificationFrame = serde_json::from_str(json).unwrap();
    assert_eq!(notif.notification.id, "abc123");
    assert_eq!(notif.notification.action, "CREATE");
}

#[tokio::test]
async fn test_error_conversion_not_leader() {
    let rpc_err = protocol::RpcError {
        code: ERR_NOT_LEADER,
        message: "not leader".into(),
        kind: Some("NOT_LEADER".into()),
        leader: Some("n1".into()),
        leader_address: Some("addr".into()),
        protocol: None,
        version: None,
    };
    let axon_err: AxonError = rpc_err.into();
    match axon_err {
        AxonError::NotLeader {
            leader,
            leader_address,
            ..
        } => {
            assert_eq!(leader, "n1");
            assert_eq!(leader_address, "addr");
        }
        _ => panic!("esperava NotLeaderError"),
    }
}

#[tokio::test]
async fn test_error_conversion_auth() {
    let rpc_err = protocol::RpcError {
        code: ERR_AUTH,
        message: "auth failed".into(),
        kind: None,
        leader: None,
        leader_address: None,
        protocol: None,
        version: None,
    };
    let axon_err: AxonError = rpc_err.into();
    match axon_err {
        AxonError::Auth(m) => assert_eq!(m, "auth failed"),
        _ => panic!("esperava AuthError"),
    }
}

#[tokio::test]
async fn test_codec_is_typed_value() {
    use serde_json::json;
    assert!(codec::is_typed_value(
        &json!({"$datetime": "2026-01-01T00:00:00Z"})
    ));
    assert!(!codec::is_typed_value(&json!({"name": "Ana"})));
    assert!(!codec::is_typed_value(&json!("hello")));
}

#[tokio::test]
async fn test_codec_encode_typed() {
    use serde_json::json;
    let v = codec::encode_typed(json!(42));
    assert_eq!(v, json!(42));

    let v = codec::encode_typed(json!("hello"));
    assert_eq!(v, json!("hello"));

    let v = codec::encode_typed(json!([1, "a"]));
    assert_eq!(v, json!([1, "a"]));

    let v = codec::encode_typed(json!({"$decimal": "99.90"}));
    assert_eq!(v, json!({"$decimal": "99.90"}));
}

#[tokio::test]
async fn test_kv_entry_deserialize() {
    let json = r#"{"key": "mykey", "value": "myvalue", "ttl": 3600}"#;
    let entry: protocol::KvEntry = serde_json::from_str(json).unwrap();
    assert_eq!(entry.key, "mykey");
    assert_eq!(entry.ttl, Some(3600));
}

#[tokio::test]
async fn test_not_leader_error_code() {
    let err = AxonError::NotLeader {
        message: "not leader".into(),
        leader: "n1".into(),
        leader_address: "addr".into(),
    };
    assert_eq!(err.code(), ERR_NOT_LEADER);
}

#[tokio::test]
async fn test_no_quorum_error_code() {
    let err = AxonError::NoQuorum("no quorum".into());
    assert_eq!(err.code(), ERR_NO_QUORUM);
}

#[tokio::test]
async fn test_connect_to_real_server() {
    // Este teste requer servidor rodando; pular por padrão
    // Para testar: cargo test -- --ignored
}

#[tokio::test]
#[ignore]
async fn test_full_integration() {
    let axon = Axon::connect("ws://127.0.0.1:8000/rpc/ws", AxonOptions::default())
        .await
        .expect("falha ao conectar");

    let hello = axon.hello_info().await;
    assert!(hello.is_some());
    assert_eq!(hello.unwrap().protocol, 1);

    let pong = axon.ping().await.expect("ping falhou");
    assert!(pong);

    axon.use_ns("test", "dev").await.expect("use falhou");
    assert_eq!(axon.namespace().await, "test");
    assert_eq!(axon.database().await, "dev");

    let v = axon.version().await.expect("version falhou");
    assert!(!v.is_empty());

    axon.close().await;
}
