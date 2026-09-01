using System.Text.Json;

namespace AxonBaseSdk;

public static class Codec
{
    private static readonly HashSet<string> TypedKeys =
    [
        "$decimal", "$bytes", "$datetime", "$duration",
        "$record", "$uuid", "$table"
    ];

    public static bool IsTypedValue(JsonElement element)
    {
        if (element.ValueKind != JsonValueKind.Object) return false;
        if (element.EnumerateObject().Count() != 1) return false;
        var key = element.EnumerateObject().First().Name;
        return TypedKeys.Contains(key);
    }

    public static bool IsTypedValue(object? value)
    {
        if (value is not JsonElement element) return false;
        return IsTypedValue(element);
    }

    public static object? EncodeTyped(object? value)
    {
        if (value is null) return null;
        if (value is bool || value is int || value is long || value is double || value is float)
            return value;
        if (value is string s) return s;
        if (value is System.Text.Json.JsonElement je) return EncodeJsonElement(je);
        if (value is System.Collections.IList list)
        {
            var result = new List<object?>();
            foreach (var item in list)
                result.Add(EncodeTyped(item));
            return result;
        }
        if (value is System.Collections.IDictionary dict)
        {
            var result = new Dictionary<string, object?>();
            foreach (System.Collections.DictionaryEntry entry in dict)
            {
                var key = entry.Key?.ToString();
                if (key != null)
                    result[key] = EncodeTyped(entry.Value);
            }
            return result;
        }
        return value.ToString();
    }

    private static object? EncodeJsonElement(JsonElement element)
    {
        return element.ValueKind switch
        {
            JsonValueKind.Null => null,
            JsonValueKind.True => true,
            JsonValueKind.False => false,
            JsonValueKind.Number => element.GetRawText(),
            JsonValueKind.String => element.GetString(),
            JsonValueKind.Array => element.EnumerateArray().Select(EncodeJsonElement).ToList(),
            JsonValueKind.Object => EncodeJsonObject(element),
            _ => element.GetRawText(),
        };
    }

    private static Dictionary<string, object?> EncodeJsonObject(JsonElement obj)
    {
        var result = new Dictionary<string, object?>();
        foreach (var prop in obj.EnumerateObject())
            result[prop.Name] = EncodeJsonElement(prop.Value);
        return result;
    }
}