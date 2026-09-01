package migration

import (
	"crypto/sha256"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"time"

	"github.com/alvaro-brito-products/axonbase/axonbase-sdk-go/axonbase"
)

const (
	defaultTable   = "_migration"
	createTableSQL = "DEFINE TABLE _migration SCHEMAFULL; DEFINE FIELD version ON TABLE _migration TYPE string; DEFINE FIELD name ON TABLE _migration TYPE string; DEFINE FIELD checksum ON TABLE _migration TYPE string; DEFINE FIELD applied_at ON TABLE _migration TYPE datetime DEFAULT time::now();"
)

type MigrationFile struct {
	Version  string
	Name     string
	Path     string
	Checksum string
}

type MigrationRecord struct {
	Version   string `json:"version"`
	Name      string `json:"name"`
	Checksum  string `json:"checksum"`
	AppliedAt string `json:"applied_at"`
}

type MigrationHook func(m MigrationFile) error

type Migrator struct {
	axon        *axonbase.Axon
	table       string
	beforeHooks []MigrationHook
	afterHooks  []MigrationHook
}

func NewMigrator(axon *axonbase.Axon, table *string) *Migrator {
	t := defaultTable
	if table != nil {
		t = *table
	}
	return &Migrator{
		axon:  axon,
		table: t,
	}
}

func (m *Migrator) OnBefore(hook MigrationHook) {
	m.beforeHooks = append(m.beforeHooks, hook)
}

func (m *Migrator) OnAfter(hook MigrationHook) {
	m.afterHooks = append(m.afterHooks, hook)
}

func (m *Migrator) EnsureTable() error {
	_, err := m.axon.Query(createTableSQL, nil)
	return err
}

func (m *Migrator) Applied() ([]MigrationRecord, error) {
	result, err := m.axon.Query(
		fmt.Sprintf("SELECT version, name, checksum, applied_at FROM %s ORDER BY version ASC", m.table),
		nil,
	)
	if err != nil {
		return nil, err
	}

	raw, ok := result.([]interface{})
	if !ok {
		return nil, nil
	}

	var records []MigrationRecord
	for _, r := range raw {
		item, ok := r.(map[string]interface{})
		if !ok {
			continue
		}
		records = append(records, MigrationRecord{
			Version:   toString(item["version"]),
			Name:      toString(item["name"]),
			Checksum:  toString(item["checksum"]),
			AppliedAt: toString(item["applied_at"]),
		})
	}
	return records, nil
}

func (m *Migrator) Load(path string) ([]MigrationFile, error) {
	info, err := os.Stat(path)
	if err != nil {
		return nil, fmt.Errorf("stat %s: %w", path, err)
	}

	if !info.IsDir() {
		if !strings.HasSuffix(path, ".axql") {
			return nil, fmt.Errorf("not an .axql file: %s", path)
		}
		f, err := parseFile(path)
		if err != nil {
			return nil, err
		}
		return []MigrationFile{f}, nil
	}

	entries, err := os.ReadDir(path)
	if err != nil {
		return nil, fmt.Errorf("read dir %s: %w", path, err)
	}

	var files []MigrationFile
	for _, entry := range entries {
		if entry.IsDir() || !strings.HasSuffix(entry.Name(), ".axql") {
			continue
		}
		f, err := parseFile(filepath.Join(path, entry.Name()))
		if err != nil {
			return nil, err
		}
		files = append(files, f)
	}

	sort.Slice(files, func(i, j int) bool {
		return files[i].Version < files[j].Version
	})

	return files, nil
}

func (m *Migrator) Status(path string) (pending []MigrationFile, applied []MigrationRecord, err error) {
	files, err := m.Load(path)
	if err != nil {
		return nil, nil, err
	}

	appliedRecords, err := m.Applied()
	if err != nil {
		return nil, nil, err
	}

	appliedSet := make(map[string]bool)
	for _, r := range appliedRecords {
		appliedSet[r.Version] = true
	}

	for _, f := range files {
		if !appliedSet[f.Version] {
			pending = append(pending, f)
		}
	}

	return pending, appliedRecords, nil
}

func (m *Migrator) Up(path string) ([]MigrationRecord, error) {
	pending, _, err := m.Status(path)
	if err != nil {
		return nil, err
	}

	var completed []MigrationRecord

	for _, migration := range pending {
		for _, hook := range m.beforeHooks {
			if err := hook(migration); err != nil {
				return completed, fmt.Errorf("before hook: %w", err)
			}
		}

		content, err := os.ReadFile(migration.Path)
		if err != nil {
			return completed, fmt.Errorf("read %s: %w", migration.Path, err)
		}

		_, err = m.axon.Query(string(content), nil)
		if err != nil {
			return completed, fmt.Errorf("execute %s: %w", migration.Name, err)
		}

		vars := map[string]interface{}{
			"v": migration.Version,
			"n": migration.Name,
			"c": migration.Checksum,
		}
		_, err = m.axon.Query(
			fmt.Sprintf("UPSERT %s:v_%s CONTENT { version: $v, name: $n, checksum: $c, applied_at: time::now() }", m.table, migration.Version),
			&vars,
		)
		if err != nil {
			return completed, fmt.Errorf("track %s: %w", migration.Name, err)
		}

		for _, hook := range m.afterHooks {
			if err := hook(migration); err != nil {
				return completed, fmt.Errorf("after hook: %w", err)
			}
		}

		completed = append(completed, MigrationRecord{
			Version:   migration.Version,
			Name:      migration.Name,
			Checksum:  migration.Checksum,
			AppliedAt: time.Now().UTC().Format(time.RFC3339),
		})
	}

	return completed, nil
}

func parseFile(filePath string) (MigrationFile, error) {
	base := filepath.Base(filePath)
	name := strings.TrimSuffix(base, ".axql")
	version := name
	if i := strings.Index(name, "_"); i > 0 {
		version = name[:i]
	}

	content, err := os.ReadFile(filePath)
	if err != nil {
		return MigrationFile{}, fmt.Errorf("read %s: %w", filePath, err)
	}

	hash := sha256.Sum256(content)
	checksum := fmt.Sprintf("%x", hash)

	return MigrationFile{
		Version:  version,
		Name:     name,
		Path:     filePath,
		Checksum: checksum,
	}, nil
}

func toString(v interface{}) string {
	if v == nil {
		return ""
	}
	s, ok := v.(string)
	if ok {
		return s
	}
	return fmt.Sprintf("%v", v)
}