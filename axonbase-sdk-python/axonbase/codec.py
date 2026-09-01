TYPED_KEYS = frozenset({
    "$decimal", "$bytes", "$datetime", "$duration", "$record", "$uuid", "$table",
})


def is_typed_value(v):
    if not isinstance(v, dict):
        return False
    if len(v) != 1:
        return False
    key = next(iter(v))
    return key in TYPED_KEYS


def typed_value_to_string(v):
    key = next(iter(v))
    return f"{key}={v[key]}"


def encode_typed(value):
    if value is None:
        return None
    if isinstance(value, (bool, int, float)):
        return value
    if isinstance(value, str):
        return value
    if isinstance(value, (list, tuple)):
        return [encode_typed(v) for v in value]
    if isinstance(value, dict):
        if is_typed_value(value):
            return value
        return {k: encode_typed(v) for k, v in value.items()}
    return str(value)