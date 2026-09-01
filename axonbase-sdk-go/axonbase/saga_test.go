package axonbase

import (
	"net/http"
	"net/http/httptest"
	"reflect"
	"strings"
	"testing"

	"github.com/gorilla/websocket"
)

func TestSagaTransactionLifecycle(t *testing.T) {
	upgrader := websocket.Upgrader{}
	var requests []RpcRequest
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
			requests = append(requests, request)
			result := interface{}(nil)
			if request.Method == "query" {
				sql := request.Params[0].(string)
				switch {
				case strings.HasPrefix(sql, "BEGIN"):
					result = map[string]string{"status": "RUNNING"}
				case strings.HasPrefix(sql, "COMMIT"):
					result = map[string]string{"status": "COMMITTED"}
				case strings.HasPrefix(sql, "SHOW"):
					result = map[string]interface{}{"status": "COMMITTED", "steps": []interface{}{}}
				default:
					result = map[string]int{"updated": 1}
				}
			}
			if err := conn.WriteJSON(RpcResponse{ID: request.ID, Result: result}); err != nil {
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

	saga := NewSagaTransaction(client, "order's", "corr\\id'")
	if _, err := saga.Step("UPDATE orders:o1 SET total = 200"); err == nil {
		t.Fatal("Step before Begin must fail")
	}
	if err := saga.Begin(); err != nil {
		t.Fatalf("Begin: %v", err)
	}
	if !saga.IsBegun() {
		t.Error("saga must be begun")
	}
	if result, err := saga.Step("UPDATE orders:o1 SET total = 200"); err != nil || !reflect.DeepEqual(result, map[string]interface{}{"updated": float64(1)}) {
		t.Errorf("Step() = %#v, %v", result, err)
	}
	if _, err := saga.Describe(); err != nil {
		t.Fatalf("Describe: %v", err)
	}
	if err := saga.Commit(); err != nil {
		t.Fatalf("Commit: %v", err)
	}
	if !saga.IsFinished() {
		t.Error("saga must be finished")
	}
	if err := saga.Commit(); err == nil {
		t.Fatal("Commit after completion must fail")
	}

	got := make([]string, len(requests))
	for i, request := range requests {
		if request.Method == "let" {
			got[i] = request.Method + ":" + request.Params[0].(string) + "=" + request.Params[1].(string)
		} else {
			got[i] = request.Method + ":" + request.Params[0].(string)
		}
	}
	want := []string{
		"query:BEGIN SAGA order''s WITH CORRELATION 'corr\\\\id'''",
		"let:saga_corr=corr\\id'",
		"query:UPDATE orders:o1 SET total = 200",
		"query:SHOW SAGA TRANSACTION order''s 'corr\\\\id'''",
		"query:COMMIT SAGA order''s WITH CORRELATION 'corr\\\\id'''",
	}
	if !reflect.DeepEqual(got, want) {
		t.Errorf("requests = %#v, want %#v", got, want)
	}
}

func TestSagaTransactionRollbackIsIdempotent(t *testing.T) {
	saga := &SagaTransaction{}
	if err := saga.Rollback(); err != nil {
		t.Errorf("Rollback before Begin: %v", err)
	}
}
