// loadtest: gerador de carga HTTP para o AxonBase (POST /sql). Mede vazão e latência (p50/p95/p99/máx) por cenário.
//
//	go run . -url http://localhost:8000 -user root -pass $AXON_ROOT_PASSWORD -ns lab -db carga -scenario mixed -conns 64 -duration 20s
//
// Cenários: setup, orders-insert, status-update, read-by-id, driver-ping, nearby, mixed.
// Rode "setup" uma vez (cria tabelas, índices e semeia -rows registros) e depois os demais cenários.
package main

import (
	"bytes"
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"math/rand"
	"net/http"
	"os"
	"sort"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

var (
	baseURL  = flag.String("url", "http://localhost:8000", "endereço do AxonBase")
	user     = flag.String("user", "root", "usuário")
	pass     = flag.String("pass", "", "senha")
	ns       = flag.String("ns", "lab", "namespace")
	db       = flag.String("db", "carga", "database")
	scenario = flag.String("scenario", "mixed", "setup|orders-insert|status-update|read-by-id|driver-ping|nearby|mixed")
	conns    = flag.Int("conns", 64, "conexões simultâneas")
	duration = flag.Duration("duration", 15*time.Second, "duração do cenário")
	rows     = flag.Int("rows", 200000, "registros semeados no setup (pedidos) e base de ids")
	drivers  = flag.Int("drivers", 100000, "entregadores semeados no setup")
	batch    = flag.Int("batch", 500, "statements por requisição no setup")
)

var token string
var client *http.Client

func post(sql string) ([]byte, int, error) {
	req, _ := http.NewRequest("POST", *baseURL+"/sql", strings.NewReader(sql))
	req.Header.Set("Authorization", "Bearer "+token)
	req.Header.Set("Axon-Ns", *ns)
	req.Header.Set("Axon-Db", *db)
	resp, err := client.Do(req)
	if err != nil {
		return nil, 0, err
	}
	defer resp.Body.Close()
	b, _ := io.ReadAll(resp.Body)
	return b, resp.StatusCode, nil
}

func signin() {
	body, _ := json.Marshal(map[string]string{"user": *user, "pass": *pass})
	resp, err := client.Post(*baseURL+"/signin", "application/json", bytes.NewReader(body))
	if err != nil {
		fmt.Fprintln(os.Stderr, "signin:", err)
		os.Exit(1)
	}
	defer resp.Body.Close()
	var out struct {
		Token string `json:"token"`
	}
	b, _ := io.ReadAll(resp.Body)
	if json.Unmarshal(b, &out) != nil || out.Token == "" {
		fmt.Fprintln(os.Stderr, "signin falhou:", string(b))
		os.Exit(1)
	}
	token = out.Token
}

func order(i int) string {
	st := []string{"novo", "preparo", "entrega", "entregue"}
	return fmt.Sprintf("{codigo: 'c%d', cliente: 'cli%d', loja: 'loja%d', status: '%s', valor: %d, itens: [{p: 'x', q: 1}, {p: 'y', q: 2}]}",
		i, i%5000, i%300, st[i%4], 10+i%500)
}

func point(r *rand.Rand) (float64, float64) {
	return -46.8 + float64(r.Intn(400))*0.001, -23.7 + float64(r.Intn(250))*0.001
}

func setup() {
	fmt.Println("setup: tabelas e índices")
	post("DEFINE TABLE pedido SCHEMALESS; CREATE INDEX pedido_cod ON TABLE pedido COLUMNS codigo UNIQUE; CREATE INDEX pedido_st ON TABLE pedido COLUMNS status COLUMNAR; DEFINE TABLE entregador SCHEMALESS; CREATE INDEX ent_pos ON TABLE entregador COLUMNS pos GEO")
	seed := func(name string, total int, gen func(i int) string) {
		t0 := time.Now()
		var wg sync.WaitGroup
		next := int64(0)
		for w := 0; w < 8; w++ {
			wg.Add(1)
			go func() {
				defer wg.Done()
				for {
					start := int(atomic.AddInt64(&next, int64(*batch))) - *batch
					if start >= total {
						return
					}
					var sb strings.Builder
					for i := start; i < start+*batch && i < total; i++ {
						sb.WriteString(gen(i))
						sb.WriteString(";")
					}
					post(sb.String())
				}
			}()
		}
		wg.Wait()
		fmt.Printf("  %s: %d registros em %.1fs (%.0f/s)\n", name, total, time.Since(t0).Seconds(), float64(total)/time.Since(t0).Seconds())
	}
	seed("pedido", *rows, func(i int) string { return fmt.Sprintf("CREATE pedido:%d CONTENT %s", i, order(i)) })
	seed("entregador", *drivers, func(i int) string {
		r := rand.New(rand.NewSource(int64(i)))
		lon, lat := point(r)
		return fmt.Sprintf("CREATE entregador:%d CONTENT {nome: 'e%d', pos: {type: 'Point', coordinates: [%f, %f]}}", i, i, lon, lat)
	})
}

type op func(r *rand.Rand, seq int64) string

func readByID(r *rand.Rand, _ int64) string { return fmt.Sprintf("SELECT * FROM pedido:%d", r.Intn(*rows)) }
func statusUpdate(r *rand.Rand, _ int64) string {
	st := []string{"novo", "preparo", "entrega", "entregue"}
	return fmt.Sprintf("UPDATE pedido:%d SET status = '%s'", r.Intn(*rows), st[r.Intn(4)])
}
func ordersInsert(r *rand.Rand, seq int64) string {
	id := *rows + int(seq)
	return fmt.Sprintf("CREATE pedido:%d CONTENT %s", id, order(id))
}
func driverPing(r *rand.Rand, _ int64) string {
	lon, lat := point(r)
	return fmt.Sprintf("UPDATE entregador:%d SET pos = {type: 'Point', coordinates: [%f, %f]}", r.Intn(*drivers), lon, lat)
}
func nearby(r *rand.Rand, _ int64) string {
	lon, lat := point(r)
	return fmt.Sprintf("SELECT nome FROM entregador WHERE geo::distance(pos, {type: 'Point', coordinates: [%f, %f]}) <= 2000 LIMIT 20", lon, lat)
}
func mixed(r *rand.Rand, seq int64) string {
	switch x := r.Intn(100); {
	case x < 55:
		return readByID(r, seq)
	case x < 75:
		return statusUpdate(r, seq)
	case x < 90:
		return driverPing(r, seq)
	case x < 97:
		return nearby(r, seq)
	default:
		return ordersInsert(r, seq)
	}
}

func main() {
	flag.Parse()
	client = &http.Client{Timeout: 60 * time.Second, Transport: &http.Transport{MaxIdleConns: 4096, MaxIdleConnsPerHost: 4096, MaxConnsPerHost: 0}}
	signin()
	if *scenario == "setup" {
		setup()
		return
	}
	ops := map[string]op{"orders-insert": ordersInsert, "status-update": statusUpdate, "read-by-id": readByID,
		"driver-ping": driverPing, "nearby": nearby, "mixed": mixed}
	fn, ok := ops[*scenario]
	if !ok {
		fmt.Fprintln(os.Stderr, "cenário desconhecido:", *scenario)
		os.Exit(2)
	}
	var seq int64
	var errs, errBody int64
	lat := make([][]float64, *conns)
	var wg sync.WaitGroup
	stop := time.Now().Add(*duration)
	t0 := time.Now()
	for c := 0; c < *conns; c++ {
		wg.Add(1)
		go func(c int) {
			defer wg.Done()
			r := rand.New(rand.NewSource(int64(c) + time.Now().UnixNano()))
			for time.Now().Before(stop) {
				s := atomic.AddInt64(&seq, 1)
				a := time.Now()
				b, code, err := post(fn(r, s))
				d := float64(time.Since(a).Microseconds()) / 1000
				if err != nil || code != 200 {
					atomic.AddInt64(&errs, 1)
					continue
				}
				if bytes.HasPrefix(b, []byte(`{"status":"ERR"`)) {
					atomic.AddInt64(&errBody, 1)
					continue
				}
				lat[c] = append(lat[c], d)
			}
		}(c)
	}
	wg.Wait()
	secs := time.Since(t0).Seconds()
	var all []float64
	for _, l := range lat {
		all = append(all, l...)
	}
	sort.Float64s(all)
	pct := func(p float64) float64 {
		if len(all) == 0 {
			return 0
		}
		return all[min(len(all)-1, int(p*float64(len(all))))]
	}
	fmt.Printf("LOAD cenario=%s conns=%d ok=%d (%.0f ops/s) p50=%.2fms p95=%.2fms p99=%.2fms max=%.1fms erros_http=%d erros_sql=%d\n",
		*scenario, *conns, len(all), float64(len(all))/secs, pct(0.5), pct(0.95), pct(0.99), pct(1.0), errs, errBody)
}

func min(a, b int) int {
	if a < b {
		return a
	}
	return b
}
