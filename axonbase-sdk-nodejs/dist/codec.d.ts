/**
 * Codec de valores tipados do AxonBase.
 *
 * No wire JSON, tipos especiais são representados por objetos marcados:
 * `{"$decimal":"..."}`, `{"$bytes":"base64"}`, `{"$datetime":"ISO8601"}`,
 * `{"$duration":"1h"}`, `{"$record":"tabela:chave"}`, `{"$uuid":"..."}`,
 * `{"$table":"nome"}`.
 */
export type AxonValue = null | boolean | number | string | AxonValue[] | {
    [key: string]: AxonValue;
} | TypedValue;
export type TypedValue = {
    $decimal: string;
} | {
    $bytes: string;
} | {
    $datetime: string;
} | {
    $duration: string;
} | {
    $record: string;
} | {
    $uuid: string;
} | {
    $table: string;
};
/** Verifica se um valor é um objeto marcado com tipo. */
export declare function isTypedValue(v: unknown): v is TypedValue;
/** Converte um valor tipado de volta para string de apresentação, para uso em logs. */
export declare function typedValueToString(v: TypedValue): string;
/** Envolve um valor AxonQL em envelope marcado, se necessário, para preservar o tipo. */
export declare function encodeTyped(value: unknown): AxonValue;
