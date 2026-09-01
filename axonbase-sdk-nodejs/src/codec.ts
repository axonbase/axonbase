/**
 * Codec de valores tipados do AxonBase.
 *
 * No wire JSON, tipos especiais são representados por objetos marcados:
 * `{"$decimal":"..."}`, `{"$bytes":"base64"}`, `{"$datetime":"ISO8601"}`,
 * `{"$duration":"1h"}`, `{"$record":"tabela:chave"}`, `{"$uuid":"..."}`,
 * `{"$table":"nome"}`.
 */
export type AxonValue =
  | null
  | boolean
  | number
  | string
  | AxonValue[]
  | { [key: string]: AxonValue }
  | TypedValue;

export type TypedValue =
  | { $decimal: string }
  | { $bytes: string }
  | { $datetime: string }
  | { $duration: string }
  | { $record: string }
  | { $uuid: string }
  | { $table: string };

/** Verifica se um valor é um objeto marcado com tipo. */
export function isTypedValue(v: unknown): v is TypedValue {
  if (typeof v !== "object" || v === null) return false;
  const keys = Object.keys(v as Record<string, unknown>);
  if (keys.length !== 1) return false;
  const key = keys[0];
  return ["$decimal", "$bytes", "$datetime", "$duration", "$record", "$uuid", "$table"].includes(key);
}

/** Converte um valor tipado de volta para string de apresentação, para uso em logs. */
export function typedValueToString(v: TypedValue): string {
  const key = Object.keys(v)[0] as keyof TypedValue;
  const value = (v as unknown as Record<string, string>)[key];
  return `${key}=${value}`;
}

/** Envolve um valor AxonQL em envelope marcado, se necessário, para preservar o tipo. */
export function encodeTyped(value: unknown): AxonValue {
  if (value === null || value === undefined) return null;
  if (typeof value === "boolean" || typeof value === "number") return value;
  if (typeof value === "string") return value;
  if (Array.isArray(value)) return value.map(encodeTyped);
  if (typeof value === "object") {
    const obj = value as Record<string, unknown>;
    // Se já é um valor marcado, retorna como está
    if (isTypedValue(obj)) return obj as TypedValue;
    const result: Record<string, AxonValue> = {};
    for (const [k, v] of Object.entries(obj)) {
      result[k] = encodeTyped(v);
    }
    return result;
  }
  return String(value);
}