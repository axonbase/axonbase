package axonbase

import (
	"crypto/tls"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"reflect"
	"strings"
	"testing"

	"github.com/gorilla/websocket"
)

func TestProtocolConstants(t *testing.T) {
	if PROTOCOL_VERSION != 1 {
		t.Errorf("PROTOCOL_VERSION = %d, want 1", PROTOCOL_VERSION)
	}

	found := false
	for _, m := range METHODS {
		if m == "query" {
			found = true
			break
		}
	}
	if !found {
		t.Error("METHODS must contain query")
	}
	for _, method := range []string{"certificate.begin", "certificate.complete"} {
		found := false
		for _, candidate := range METHODS {
			if candidate == method {
				found = true
				break
			}
		}
		if !found {
			t.Errorf("METHODS must contain %q", method)
		}
	}
}

func TestCertificateHelpers(t *testing.T) {
	upgrader := websocket.Upgrader{}
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		conn, err := upgrader.Upgrade(w, r, nil)
		if err != nil {
			t.Errorf("upgrade: %v", err)
			return
		}
		defer conn.Close()
		if err := conn.WriteJSON(HelloFrame{Hello: HelloPayload{Protocol: PROTOCOL_VERSION}}); err != nil {
			t.Errorf("write hello: %v", err)
			return
		}
		for {
			var request RpcRequest
			if err := conn.ReadJSON(&request); err != nil {
				return
			}
			switch request.Method {
			case "certificate.begin":
				if got := request.Params; len(got) != 1 || got[0].(map[string]interface{})["store"] != "icp-brasil" {
					t.Errorf("certificate.begin params = %#v", got)
				}
				err = conn.WriteJSON(RpcResponse{ID: request.ID, Result: map[string]string{
					"id": "challenge-id", "challenge": "nonce", "expires_at": "2026-01-01T00:00:00Z",
				}})
			case "certificate.complete":
				const want = `[{"id":"challenge-id","challenge":"nonce","store":"icp-brasil","user":"12345678901","chain":["leaf","issuer"],"signature":"signature-base64"}]`
				var wantParams []interface{}
				if err := json.Unmarshal([]byte(want), &wantParams); err != nil {
					t.Fatalf("unmarshal expected params: %v", err)
				}
				if !reflect.DeepEqual(request.Params, wantParams) {
					t.Errorf("certificate.complete params = %#v, want %#v", request.Params, wantParams)
				}
				err = conn.WriteJSON(RpcResponse{ID: request.ID, Result: "temporary-credential"})
			}
			if err != nil {
				t.Errorf("write response: %v", err)
				return
			}
		}
	}))
	defer server.Close()

	client, err := Connect("ws"+strings.TrimPrefix(server.URL, "http"), nil)
	if err != nil {
		t.Fatalf("connect: %v", err)
	}
	defer client.Close()

	challenge, err := client.CertificateBegin("icp-brasil")
	if err != nil {
		t.Fatalf("CertificateBegin: %v", err)
	}
	if want := (CertificateChallenge{ID: "challenge-id", Challenge: "nonce", ExpiresAt: "2026-01-01T00:00:00Z"}); challenge != want {
		t.Errorf("CertificateBegin() = %#v, want %#v", challenge, want)
	}
	credential, err := client.CertificateComplete(CertificateCompletion{
		ID: "challenge-id", Challenge: "nonce", Store: "icp-brasil", User: "12345678901",
		Chain: []string{"leaf", "issuer"}, Signature: "signature-base64",
	})
	if err != nil {
		t.Fatalf("CertificateComplete: %v", err)
	}
	if credential != "temporary-credential" {
		t.Errorf("CertificateComplete() = %q, want temporary-credential", credential)
	}
}

func TestTLSConfigPropagatesToDedicatedDialer(t *testing.T) {
	config := &tls.Config{ServerName: "example.test"}
	client := NewAxon("wss://example.test/rpc/ws", &AxonOptions{TLSConfig: config})
	if client.dialer == websocket.DefaultDialer {
		t.Error("client must use a dedicated dialer")
	}
	if client.dialer.TLSClientConfig != config {
		t.Error("client dialer must use the supplied TLS config")
	}
}

func TestErrorTypes(t *testing.T) {
	e := newNotLeader("msg", "n1", "addr")
	if e.Code != ERR_NOT_LEADER {
		t.Errorf("NotLeaderError code = %d, want %d", e.Code, ERR_NOT_LEADER)
	}
	if e.Leader != "n1" {
		t.Errorf("NotLeaderError leader = %s, want n1", e.Leader)
	}

	e2 := newNoQuorum("no quorum")
	if e2.Code != ERR_NO_QUORUM {
		t.Errorf("NoQuorumError code = %d, want %d", e2.Code, ERR_NO_QUORUM)
	}

	e3 := newAuth("bad auth")
	if e3.Code != ERR_AUTH {
		t.Errorf("AuthError code = %d, want %d", e3.Code, ERR_AUTH)
	}
}

func TestFromRpcError(t *testing.T) {
	e := FromRpcError(&RpcError{Code: ERR_NOT_LEADER, Message: "not leader", Leader: "n1", LeaderAddress: "addr"})
	if _, ok := e.(*NotLeaderError); !ok {
		t.Error("expected NotLeaderError")
	}

	e = FromRpcError(&RpcError{Code: ERR_NO_QUORUM, Message: "no quorum"})
	if _, ok := e.(*NoQuorumError); !ok {
		t.Error("expected NoQuorumError")
	}

	e = FromRpcError(&RpcError{Code: ERR_AUTH, Message: "auth"})
	if _, ok := e.(*AuthError); !ok {
		t.Error("expected AuthError")
	}
}

func TestCodec(t *testing.T) {
	if IsTypedValue(nil) {
		t.Error("nil não é typed value")
	}
	if IsTypedValue("hello") {
		t.Error("string não é typed value")
	}
	if !IsTypedValue(map[string]interface{}{"$datetime": "2026-01-01T00:00:00Z"}) {
		t.Error("$datetime deve ser typed value")
	}
	if IsTypedValue(map[string]interface{}{"name": "Ana"}) {
		t.Error("objeto comum não é typed value")
	}

	r := EncodeTyped(42)
	if r != 42 {
		t.Errorf("EncodeTyped(42) = %v, want 42", r)
	}

	r = EncodeTyped([]interface{}{1, "a"})
	arr, ok := r.([]interface{})
	if !ok || len(arr) != 2 {
		t.Error("EncodeTyped array falhou")
	}
}

func TestRpcRequestJSON(t *testing.T) {
	req := RpcRequest{
		ID:      1,
		Method:  "ping",
		Params:  []interface{}{},
		Version: PROTOCOL_VERSION,
	}
	data, err := json.Marshal(req)
	if err != nil {
		t.Fatalf("json.Marshal falhou: %v", err)
	}
	var parsed map[string]interface{}
	if err := json.Unmarshal(data, &parsed); err != nil {
		t.Fatalf("json.Unmarshal falhou: %v", err)
	}
	if parsed["method"] != "ping" {
		t.Errorf("method = %v, want ping", parsed["method"])
	}
}

func TestHelloFrameJSON(t *testing.T) {
	data := `{"hello": {"protocol": 1, "server": "0.1.0-SNAPSHOT", "methods": ["ping", "query"]}}`
	var hello HelloFrame
	if err := json.Unmarshal([]byte(data), &hello); err != nil {
		t.Fatalf("json.Unmarshal falhou: %v", err)
	}
	if hello.Hello.Protocol != 1 {
		t.Errorf("protocol = %d, want 1", hello.Hello.Protocol)
	}
	if hello.Hello.Server != "0.1.0-SNAPSHOT" {
		t.Errorf("server = %s", hello.Hello.Server)
	}
}

func TestKvEntryJSON(t *testing.T) {
	t.Skip("kv_scan depende de servidor")
}
