package axonbase

import (
	"fmt"
	"strings"
)

var typedKeys = map[string]bool{
	"$decimal":   true,
	"$bytes":     true,
	"$datetime":  true,
	"$duration":  true,
	"$record":    true,
	"$uuid":      true,
	"$table":     true,
}

func IsTypedValue(v interface{}) bool {
	m, ok := v.(map[string]interface{})
	if !ok || len(m) != 1 {
		return false
	}
	for k := range m {
		return typedKeys[k]
	}
	return false
}

func TypedValueToString(v interface{}) string {
	m, ok := v.(map[string]interface{})
	if !ok || len(m) != 1 {
		return ""
	}
	for k, val := range m {
		return k + "=" + fmtAny(val)
	}
	return ""
}

func EncodeTyped(value interface{}) interface{} {
	if value == nil {
		return nil
	}
	switch v := value.(type) {
	case bool, int, int64, float64:
		return v
	case string:
		return v
	case []interface{}:
		result := make([]interface{}, len(v))
		for i, x := range v {
			result[i] = EncodeTyped(x)
		}
		return result
	case map[string]interface{}:
		if IsTypedValue(v) {
			return v
		}
		result := make(map[string]interface{}, len(v))
		for k, x := range v {
			result[k] = EncodeTyped(x)
		}
		return result
	default:
		return strings.TrimSpace(fmtAny(value))
	}
}

func fmtAny(v interface{}) string {
	if s, ok := v.(string); ok {
		return s
	}
	return fmt.Sprint(v)
}
