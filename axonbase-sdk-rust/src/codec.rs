use serde_json::Value;

const TYPED_KEYS: &[&str] = &[
    "$decimal",
    "$bytes",
    "$datetime",
    "$duration",
    "$record",
    "$uuid",
    "$table",
];

pub fn is_typed_value(v: &Value) -> bool {
    match v {
        Value::Object(map) => {
            if map.len() != 1 {
                return false;
            }
            let key = map.keys().next().unwrap();
            TYPED_KEYS.contains(&key.as_str())
        }
        _ => false,
    }
}

pub fn typed_value_to_string(v: &Value) -> String {
    match v {
        Value::Object(map) if map.len() == 1 => {
            let key = map.keys().next().unwrap();
            let val = map.values().next().unwrap();
            format!("{}={}", key, val)
        }
        _ => String::new(),
    }
}

pub fn encode_typed(value: Value) -> Value {
    match value {
        Value::Null => Value::Null,
        Value::Bool(_) | Value::Number(_) | Value::String(_) => value,
        Value::Array(arr) => Value::Array(arr.into_iter().map(encode_typed).collect()),
        Value::Object(map) => {
            if map.len() == 1 {
                let key = map.keys().next().unwrap().clone();
                if TYPED_KEYS.contains(&key.as_str()) {
                    return Value::Object(map);
                }
            }
            let result: serde_json::Map<String, Value> =
                map.into_iter().map(|(k, v)| (k, encode_typed(v))).collect();
            Value::Object(result)
        }
    }
}
