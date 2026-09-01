package axonbase

import (
	"net/http"
	"net/http/httptest"
	"reflect"
	"strings"
	"testing"

	"github.com/gorilla/websocket"
)

func TestSagaParticipantTransactionUsesOnlyLocalTransactionRPCs(t *testing.T) {
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
			if err := conn.WriteJSON(RpcResponse{ID: request.ID, Result: map[string]bool{"ok": true}}); err != nil {
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

	participant := NewSagaParticipantTransaction(client, "corr-123")
	if err := participant.Begin(); err != nil {
		t.Fatalf("Begin: %v", err)
	}
	if _, err := participant.Step("UPDATE orders:o1 SET total = 200"); err != nil {
		t.Fatalf("Step: %v", err)
	}
	if err := participant.Commit(); err != nil {
		t.Fatalf("Commit: %v", err)
	}

	got := make([]string, len(requests))
	for i, request := range requests {
		if request.Method == "let" {
			got[i] = request.Method + ":" + request.Params[0].(string) + "=" + request.Params[1].(string)
		} else if request.Method == "query" {
			got[i] = request.Method + ":" + request.Params[0].(string)
		} else {
			got[i] = request.Method
		}
	}
	want := []string{"begin", "let:saga_corr=corr-123", "query:UPDATE orders:o1 SET total = 200", "commit"}
	if !reflect.DeepEqual(got, want) {
		t.Errorf("requests = %#v, want %#v", got, want)
	}
}
