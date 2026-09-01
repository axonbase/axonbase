"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.isTypedValue = isTypedValue;
exports.typedValueToString = typedValueToString;
exports.encodeTyped = encodeTyped;
/** Verifica se um valor é um objeto marcado com tipo. */
function isTypedValue(v) {
    if (typeof v !== "object" || v === null)
        return false;
    const keys = Object.keys(v);
    if (keys.length !== 1)
        return false;
    const key = keys[0];
    return ["$decimal", "$bytes", "$datetime", "$duration", "$record", "$uuid", "$table"].includes(key);
}
/** Converte um valor tipado de volta para string de apresentação, para uso em logs. */
function typedValueToString(v) {
    const key = Object.keys(v)[0];
    const value = v[key];
    return `${key}=${value}`;
}
/** Envolve um valor AxonQL em envelope marcado, se necessário, para preservar o tipo. */
function encodeTyped(value) {
    if (value === null || value === undefined)
        return null;
    if (typeof value === "boolean" || typeof value === "number")
        return value;
    if (typeof value === "string")
        return value;
    if (Array.isArray(value))
        return value.map(encodeTyped);
    if (typeof value === "object") {
        const obj = value;
        // Se já é um valor marcado, retorna como está
        if (isTypedValue(obj))
            return obj;
        const result = {};
        for (const [k, v] of Object.entries(obj)) {
            result[k] = encodeTyped(v);
        }
        return result;
    }
    return String(value);
}
