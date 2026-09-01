using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Runtime.CompilerServices;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Threading.Tasks;

[assembly: InternalsVisibleTo("AxonBaseSdk.Tests")]

namespace AxonBaseSdk.Migration;

public record MigrationFile(string Version, string Name, string Path, string Checksum);

public record MigrationRecord(string Version, string Name, string Checksum, string AppliedAt);

public delegate Task MigrationHook(MigrationFile migration);

public class Migrator
{
    private readonly AxonClient _axon;
    private readonly string _table;
    private readonly List<MigrationHook> _beforeHooks = [];
    private readonly List<MigrationHook> _afterHooks = [];

    private const string DefaultTable = "_migration";
    private const string CreateTableSql =
        "DEFINE TABLE _migration SCHEMAFULL; DEFINE FIELD version ON TABLE _migration TYPE string; DEFINE FIELD name ON TABLE _migration TYPE string; DEFINE FIELD checksum ON TABLE _migration TYPE string; DEFINE FIELD applied_at ON TABLE _migration TYPE datetime DEFAULT time::now();";

    public Migrator(AxonClient axon, string? table = null)
    {
        _axon = axon;
        _table = table ?? DefaultTable;
    }

    public void OnBefore(MigrationHook hook) => _beforeHooks.Add(hook);

    public void OnAfter(MigrationHook hook) => _afterHooks.Add(hook);

    public async Task EnsureTableAsync()
    {
        await _axon.QueryAsync(CreateTableSql);
    }

    public async Task<List<MigrationRecord>> AppliedAsync()
    {
        var result = await _axon.QueryAsync(
            $"SELECT version, name, checksum, applied_at FROM {_table} ORDER BY version ASC");
        var json = JsonSerializer.Serialize(result, AxonClientExtensions.JsonOpts);
        if (json is "null" or "[]" or "{}")
            return [];
        var docs = JsonSerializer.Deserialize<List<JsonElement>>(json, AxonClientExtensions.JsonOpts) ?? [];
        return docs
            .Where(d => d.ValueKind == JsonValueKind.Object)
            .Select(d => new MigrationRecord(
                GetString(d, "version"),
                GetString(d, "name"),
                GetString(d, "checksum"),
                GetString(d, "applied_at")))
            .ToList();
    }

    private static string GetString(JsonElement doc, string prop)
    {
        if (!doc.TryGetProperty(prop, out var el))
            return "";
        if (el.ValueKind == JsonValueKind.String)
            return el.GetString() ?? "";
        if (el.ValueKind == JsonValueKind.Object &&
            el.TryGetProperty("$datetime", out var dt) &&
            dt.ValueKind == JsonValueKind.String)
            return dt.GetString() ?? "";
        return "";
    }

    public List<MigrationFile> Load(string path)
    {
        var resolved = Path.GetFullPath(path);

        if (File.Exists(resolved))
        {
            if (!resolved.EndsWith(".axql"))
                throw new AxonSdkException($"not an .axql file: {resolved}");
            return [ParseFile(resolved)];
        }

        if (!Directory.Exists(resolved))
            throw new AxonSdkException($"path not found: {resolved}");

        return Directory.GetFiles(resolved, "*.axql")
            .OrderBy(f => Path.GetFileName(f))
            .Select(ParseFile)
            .ToList();
    }

    public async Task<(List<MigrationFile> Pending, List<MigrationRecord> Applied)> StatusAsync(string path)
    {
        var files = Load(path);
        var applied = await AppliedAsync();
        var appliedVersions = new HashSet<string>(applied.Select(r => r.Version));
        var pending = files.Where(f => !appliedVersions.Contains(f.Version)).ToList();
        return (pending, applied);
    }

    public async Task<List<MigrationRecord>> UpAsync(string path)
    {
        var (pending, _) = await StatusAsync(path);
        var completed = new List<MigrationRecord>();

        foreach (var migration in pending)
        {
            foreach (var hook in _beforeHooks)
                await hook(migration);

            var sql = await File.ReadAllTextAsync(migration.Path);
            await _axon.QueryAsync(sql);

            var vars = new Dictionary<string, object?>
            {
                ["v"] = migration.Version,
                ["n"] = migration.Name,
                ["c"] = migration.Checksum,
            };
            await _axon.QueryAsync(
                $"UPSERT {_table}:v_{migration.Version} CONTENT {{ version: $v, name: $n, checksum: $c, applied_at: time::now() }}", vars);

            foreach (var hook in _afterHooks)
                await hook(migration);

            completed.Add(new MigrationRecord(migration.Version, migration.Name, migration.Checksum, DateTime.UtcNow.ToString("o")));
        }

        return completed;
    }

    internal static MigrationFile ParseFile(string filePath)
    {
        var fileName = Path.GetFileName(filePath);
        var name = fileName.EndsWith(".axql") ? fileName[..^5] : fileName;
        var underscore = name.IndexOf('_');
        var version = underscore > 0 ? name[..underscore] : name;

        var content = File.ReadAllBytes(filePath);
        var checksum = SHA256.HashData(content);
        var checksumHex = Convert.ToHexStringLower(checksum);

        return new MigrationFile(version, name, filePath, checksumHex);
    }
}

internal static class AxonClientExtensions
{
    internal static readonly JsonSerializerOptions JsonOpts = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        PropertyNameCaseInsensitive = true,
    };
}