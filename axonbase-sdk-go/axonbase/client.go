package axonbase

import (
	"crypto/tls"
	"encoding/json"
	"fmt"
	"log"
	"net/url"
	"strings"
	"sync"
	"time"

	"github.com/gorilla/websocket"
)

type LiveHandler func(id string, action string, result interface{})

type AxonOptions struct {
	Timeout              time.Duration
	Reconnect            bool
	ReconnectInterval    time.Duration
	MaxReconnectAttempts int
	// TLSConfig is caller-owned and is used without weakening certificate verification.
	TLSConfig *tls.Config
}

type Axon struct {
	url           string
	timeout       time.Duration
	reconnect     bool
	reconnectIntv time.Duration
	maxReconn     int
	dialer        *websocket.Dialer

	mu                sync.Mutex
	ws                *websocket.Conn
	nextID            int
	pending           map[int]chan RpcResponse
	liveHandlers      map[string]LiveHandler
	handshakeReceived bool
	namespace         string
	database          string
	auth              string
	hello             *HelloPayload
	closed            bool
}

func NewAxon(rawURL string, opts *AxonOptions) *Axon {
	if opts == nil {
		opts = &AxonOptions{}
	}
	if opts.Timeout == 0 {
		opts.Timeout = 30 * time.Second
	}
	if opts.ReconnectInterval == 0 {
		opts.ReconnectInterval = 1 * time.Second
	}
	if opts.MaxReconnectAttempts == 0 {
		opts.MaxReconnectAttempts = 10
	}
	dialer := *websocket.DefaultDialer
	dialer.TLSClientConfig = opts.TLSConfig
	return &Axon{
		url:           rawURL,
		timeout:       opts.Timeout,
		reconnect:     opts.Reconnect,
		reconnectIntv: opts.ReconnectInterval,
		maxReconn:     opts.MaxReconnectAttempts,
		dialer:        &dialer,
		pending:       make(map[int]chan RpcResponse),
		liveHandlers:  make(map[string]LiveHandler),
		nextID:        1,
	}
}

func Connect(rawURL string, opts *AxonOptions) (*Axon, error) {
	ax := NewAxon(rawURL, opts)
	if err := ax.open(); err != nil {
		return nil, err
	}
	return ax, nil
}

func (ax *Axon) open() error {
	ax.mu.Lock()
	if ax.ws != nil {
		ax.mu.Unlock()
		return nil
	}
	ax.mu.Unlock()

	u, err := url.Parse(ax.url)
	if err != nil {
		return fmt.Errorf("url inválida: %w", err)
	}
	if !strings.HasPrefix(u.Scheme, "ws") {
		return fmt.Errorf("esquema deve ser ws ou wss: %s", u.Scheme)
	}

	conn, _, err := ax.dialer.Dial(ax.url, nil)
	if err != nil {
		return err
	}
	ax.mu.Lock()
	ax.ws = conn
	ax.pending = make(map[int]chan RpcResponse)
	ax.handshakeReceived = false
	ax.mu.Unlock()

	_, msg, err := conn.ReadMessage()
	if err != nil {
		conn.Close()
		return err
	}
	var hello HelloFrame
	if err := json.Unmarshal(msg, &hello); err != nil || hello.Hello.Protocol == 0 {
		conn.Close()
		return fmt.Errorf("handshake hello esperado como primeiro frame")
	}
	ax.mu.Lock()
	ax.hello = &hello.Hello
	ax.handshakeReceived = true
	ax.mu.Unlock()

	go ax.readLoop()
	return nil
}

func (ax *Axon) readLoop() {
	for {
		_, msg, err := ax.ws.ReadMessage()
		if err != nil {
			ax.handleClose()
			return
		}

		var parsed map[string]interface{}
		if err := json.Unmarshal(msg, &parsed); err != nil {
			continue
		}

		if n, ok := parsed["notification"]; ok {
			notif, ok := n.(map[string]interface{})
			if !ok {
				continue
			}
			id, _ := notif["id"].(string)
			action, _ := notif["action"].(string)
			result := notif["result"]
			ax.mu.Lock()
			handler, exists := ax.liveHandlers[id]
			ax.mu.Unlock()
			if exists && handler != nil {
				handler(id, action, result)
			}
			continue
		}

		rpcID, ok := parsed["id"].(float64)
		if !ok {
			continue
		}
		intID := int(rpcID)
		var resp RpcResponse
		respBytes, _ := json.Marshal(parsed)
		json.Unmarshal(respBytes, &resp)

		ax.mu.Lock()
		ch, exists := ax.pending[intID]
		if exists {
			delete(ax.pending, intID)
		}
		ax.mu.Unlock()
		if exists {
			ch <- resp
		}
	}
}

func (ax *Axon) handleClose() {
	ax.mu.Lock()
	for _, ch := range ax.pending {
		close(ch)
	}
	ax.pending = make(map[int]chan RpcResponse)
	ax.ws = nil
	ax.handshakeReceived = false
	ax.mu.Unlock()

	if !ax.closed && ax.reconnect {
		go ax.reconnectLoop(0)
	}
}

func (ax *Axon) reconnectLoop(attempt int) {
	if attempt >= ax.maxReconn {
		return
	}
	time.Sleep(ax.reconnectIntv)
	ax.mu.Lock()
	oldNS := ax.namespace
	oldAuth := ax.auth
	ax.mu.Unlock()

	if err := ax.open(); err != nil {
		ax.reconnectLoop(attempt + 1)
		return
	}

	if oldNS != "" {
		parts := strings.SplitN(oldNS, "/", 2)
		if len(parts) == 2 {
			ax.callRaw("use", []interface{}{parts[0], parts[1]})
		}
	}
	if oldAuth != "" {
		ax.callRaw("authenticate", []interface{}{oldAuth})
	}
}

func (ax *Axon) callRaw(method string, params []interface{}) (RpcResponse, error) {
	ax.mu.Lock()
	if ax.ws == nil {
		ax.mu.Unlock()
		return RpcResponse{}, AxonSdkError{Code: 0, Message: "conexão não aberta"}
	}
	id := ax.nextID
	ax.nextID++
	ch := make(chan RpcResponse, 1)
	ax.pending[id] = ch
	ax.mu.Unlock()

	encParams := make([]interface{}, len(params))
	for i, p := range params {
		encParams[i] = EncodeTyped(p)
	}
	req := RpcRequest{
		ID:      id,
		Method:  method,
		Params:  encParams,
		Version: PROTOCOL_VERSION,
	}
	data, _ := json.Marshal(req)

	ax.mu.Lock()
	ws := ax.ws
	ax.mu.Unlock()
	if ws == nil {
		return RpcResponse{}, AxonSdkError{Message: "conexão não aberta"}
	}
	if err := ws.WriteMessage(websocket.TextMessage, data); err != nil {
		ax.mu.Lock()
		delete(ax.pending, id)
		ax.mu.Unlock()
		return RpcResponse{}, err
	}

	select {
	case resp := <-ch:
		return resp, nil
	case <-time.After(ax.timeout):
		ax.mu.Lock()
		delete(ax.pending, id)
		ax.mu.Unlock()
		return RpcResponse{}, AxonSdkError{Message: fmt.Sprintf("timeout em %s", method)}
	}
}

func (ax *Axon) call(method string, params []interface{}, maxRetries ...int) (interface{}, error) {
	retries := 3
	if len(maxRetries) > 0 {
		retries = maxRetries[0]
	}
	var lastErr error
	for attempt := 0; attempt < retries; attempt++ {
		resp, err := ax.callRaw(method, params)
		if err != nil {
			lastErr = err
			if attempt < retries-1 && ax.reconnect {
				time.Sleep(ax.reconnectIntv)
				ax.open()
				continue
			}
			break
		}
		if resp.Error != nil {
			e := FromRpcError(resp.Error)
			if nl, ok := e.(*NotLeaderError); ok && nl.LeaderAddress != "" && attempt < retries-1 {
				scheme := "ws://"
				if strings.HasPrefix(ax.url, "wss://") {
					scheme = "wss://"
				}
				newURL := scheme + nl.LeaderAddress + "/rpc/ws"
				ax.reconnectTo(newURL)
				continue
			}
			return nil, e
		}
		return resp.Result, nil
	}
	return nil, lastErr
}

func (ax *Axon) reconnectTo(newURL string) error {
	ax.mu.Lock()
	if ax.ws != nil {
		ax.ws.Close()
	}
	ax.ws = nil
	ax.handshakeReceived = false
	ax.hello = nil
	ax.url = newURL
	ax.mu.Unlock()

	if err := ax.open(); err != nil {
		return err
	}
	ax.mu.Lock()
	oldNS := ax.namespace
	oldDB := ax.database
	oldAuth := ax.auth
	ax.mu.Unlock()
	if oldNS != "" {
		ax.callRaw("use", []interface{}{oldNS, oldDB})
	}
	if oldAuth != "" {
		ax.callRaw("authenticate", []interface{}{oldAuth})
	}
	return nil
}

func (ax *Axon) Ping() (bool, error) {
	res, err := ax.call("ping", []interface{}{})
	if err != nil {
		return false, err
	}
	b, _ := res.(bool)
	return b, nil
}

func (ax *Axon) Use(ns, db string) (*map[string]string, error) {
	res, err := ax.call("use", []interface{}{ns, db})
	if err != nil {
		return nil, err
	}
	m, ok := res.(map[string]interface{})
	if !ok {
		return nil, AxonSdkError{Message: "use returned unexpected type"}
	}
	ax.mu.Lock()
	if ns, ok := m["namespace"].(string); ok {
		ax.namespace = ns
	}
	if db, ok := m["database"].(string); ok {
		ax.database = db
	}
	ax.mu.Unlock()
	result := map[string]string{}
	for k, v := range m {
		if s, ok := v.(string); ok {
			result[k] = s
		}
	}
	return &result, nil
}

func (ax *Axon) Query(sql string, vars *map[string]interface{}) (interface{}, error) {
	if vars != nil {
		return ax.call("query", []interface{}{sql, *vars})
	}
	return ax.call("query", []interface{}{sql})
}

func (ax *Axon) Signin(user, pass string, access *string) (string, error) {
	cred := map[string]interface{}{"user": user, "pass": pass}
	if access != nil {
		cred["access"] = *access
	}
	res, err := ax.call("signin", []interface{}{cred})
	if err != nil {
		return "", err
	}
	token, _ := res.(string)
	ax.mu.Lock()
	ax.auth = token
	ax.mu.Unlock()
	return token, nil
}

func (ax *Axon) Authenticate(token string) error {
	_, err := ax.call("authenticate", []interface{}{token})
	if err != nil {
		return err
	}
	ax.mu.Lock()
	ax.auth = token
	ax.mu.Unlock()
	return nil
}

func (ax *Axon) Signup(user, pass string) (string, error) {
	res, err := ax.call("signup", []interface{}{map[string]interface{}{"user": user, "pass": pass}})
	if err != nil {
		return "", err
	}
	token, _ := res.(string)
	ax.mu.Lock()
	ax.auth = token
	ax.mu.Unlock()
	return token, nil
}

func (ax *Axon) CertificateBegin(store string) (CertificateChallenge, error) {
	res, err := ax.call("certificate.begin", []interface{}{map[string]interface{}{"store": store}})
	if err != nil {
		return CertificateChallenge{}, err
	}
	data, err := json.Marshal(res)
	if err != nil {
		return CertificateChallenge{}, AxonSdkError{Message: "invalid certificate.begin response"}
	}
	var challenge CertificateChallenge
	if err := json.Unmarshal(data, &challenge); err != nil {
		return CertificateChallenge{}, AxonSdkError{Message: "invalid certificate.begin response"}
	}
	return challenge, nil
}

func (ax *Axon) CertificateComplete(completion CertificateCompletion) (string, error) {
	chain := make([]interface{}, len(completion.Chain))
	for i, certificate := range completion.Chain {
		chain[i] = certificate
	}
	res, err := ax.call("certificate.complete", []interface{}{map[string]interface{}{
		"id":        completion.ID,
		"challenge": completion.Challenge,
		"store":     completion.Store,
		"user":      completion.User,
		"chain":     chain,
		"signature": completion.Signature,
	}})
	if err != nil {
		return "", err
	}
	credential, ok := res.(string)
	if !ok {
		return "", AxonSdkError{Message: "invalid certificate.complete response"}
	}
	return credential, nil
}

func (ax *Axon) Invalidate() error {
	_, err := ax.call("invalidate", []interface{}{})
	if err != nil {
		return err
	}
	ax.mu.Lock()
	ax.auth = ""
	ax.mu.Unlock()
	return nil
}

func (ax *Axon) Version() (string, error) {
	res, err := ax.call("version", []interface{}{})
	if err != nil {
		return "", err
	}
	s, _ := res.(string)
	return s, nil
}

func (ax *Axon) Select(what string) (interface{}, error) {
	return ax.call("select", []interface{}{what})
}

func (ax *Axon) Create(what string, data map[string]interface{}) (interface{}, error) {
	return ax.call("create", []interface{}{what, data})
}

func (ax *Axon) Update(what string, data map[string]interface{}) (interface{}, error) {
	return ax.call("update", []interface{}{what, data})
}

func (ax *Axon) Upsert(what string, data map[string]interface{}) (interface{}, error) {
	return ax.call("upsert", []interface{}{what, data})
}

func (ax *Axon) Delete(what string) (interface{}, error) {
	return ax.call("delete", []interface{}{what})
}

func (ax *Axon) Insert(table string, data map[string]interface{}) (interface{}, error) {
	return ax.call("insert", []interface{}{table, data})
}

func (ax *Axon) Relate(from, kind, to string, data map[string]interface{}) (interface{}, error) {
	if data == nil {
		data = map[string]interface{}{}
	}
	return ax.call("relate", []interface{}{from, kind, to, data})
}

func (ax *Axon) LetVar(name string, value interface{}) error {
	clean := strings.TrimPrefix(name, "$")
	_, err := ax.callRaw("let", []interface{}{clean, value})
	return err
}

func (ax *Axon) Unset(name string) error {
	clean := strings.TrimPrefix(name, "$")
	_, err := ax.callRaw("unset", []interface{}{clean})
	return err
}

func (ax *Axon) Begin() error {
	_, err := ax.call("begin", []interface{}{})
	return err
}

func (ax *Axon) Commit() error {
	_, err := ax.call("commit", []interface{}{})
	return err
}

func (ax *Axon) Cancel() error {
	_, err := ax.call("cancel", []interface{}{})
	return err
}

func (ax *Axon) KvGet(ns, db, key string) (interface{}, error) {
	return ax.call("kv_get", []interface{}{ns, db, key})
}

func (ax *Axon) KvSet(ns, db, key string, value interface{}, ttl *int) (interface{}, error) {
	return ax.call("kv_set", []interface{}{ns, db, key, value, ttl})
}

func (ax *Axon) KvDel(ns, db, key string) (bool, error) {
	res, err := ax.call("kv_del", []interface{}{ns, db, key})
	if err != nil {
		return false, err
	}
	b, _ := res.(bool)
	return b, nil
}

func (ax *Axon) KvScan(ns, db, prefix string) ([]KvEntry, error) {
	res, err := ax.call("kv_scan", []interface{}{ns, db, prefix})
	if err != nil {
		return nil, err
	}
	if res == nil {
		return nil, nil
	}
	data, err := json.Marshal(res)
	if err != nil {
		return nil, err
	}
	var entries []KvEntry
	if err := json.Unmarshal(data, &entries); err != nil {
		return nil, err
	}
	return entries, nil
}

func (ax *Axon) Live(table string, handler LiveHandler, diff bool) (string, error) {
	res, err := ax.call("live", []interface{}{table, diff})
	if err != nil {
		return "", err
	}
	id, _ := res.(string)
	if id != "" {
		ax.mu.Lock()
		ax.liveHandlers[id] = handler
		ax.mu.Unlock()
	}
	return id, nil
}

func (ax *Axon) Kill(id string) (bool, error) {
	res, err := ax.call("kill", []interface{}{id})
	if err != nil {
		return false, err
	}
	b, _ := res.(bool)
	if b {
		ax.mu.Lock()
		delete(ax.liveHandlers, id)
		ax.mu.Unlock()
	}
	return b, nil
}

func (ax *Axon) Namespace() string {
	ax.mu.Lock()
	defer ax.mu.Unlock()
	return ax.namespace
}

func (ax *Axon) Database() string {
	ax.mu.Lock()
	defer ax.mu.Unlock()
	return ax.database
}

func (ax *Axon) IsConnected() bool {
	ax.mu.Lock()
	defer ax.mu.Unlock()
	return ax.ws != nil
}

func (ax *Axon) HelloInfo() *HelloPayload {
	ax.mu.Lock()
	defer ax.mu.Unlock()
	return ax.hello
}

func (ax *Axon) Close() {
	ax.mu.Lock()
	ax.closed = true
	ax.liveHandlers = make(map[string]LiveHandler)
	if ax.ws != nil {
		ax.ws.Close()
		ax.ws = nil
	}
	ax.mu.Unlock()
}

func init() {
	_ = log.Printf
}
