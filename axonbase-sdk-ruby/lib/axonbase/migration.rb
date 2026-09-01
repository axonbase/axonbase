# frozen_string_literal: true

require "digest"

module AxonBase
  Migration = Struct.new(:version, :name, :path, :checksum, :sql, keyword_init: true)

  class Migrator
    DEFAULT_TABLE = "_migration"

    def initialize(axon, table: DEFAULT_TABLE)
      @axon = axon
      @table = table
      @before_hooks = []
      @after_hooks = []
    end

    def on_before(&hook)
      @before_hooks << hook
      self
    end

    def on_after(&hook)
      @after_hooks << hook
      self
    end

    def ensure_table
      @axon.query("DEFINE TABLE #{@table} SCHEMAFULL;" \
                  "DEFINE FIELD version ON TABLE #{@table} TYPE string;" \
                  "DEFINE FIELD name ON TABLE #{@table} TYPE string;" \
                  "DEFINE FIELD checksum ON TABLE #{@table} TYPE string;" \
                  "DEFINE FIELD applied_at ON TABLE #{@table} TYPE datetime DEFAULT time::now();")
      nil
    end

    def applied
      rows = @axon.query("SELECT version, checksum FROM #{@table} ORDER BY version ASC;")
      return {} unless rows.is_a?(Array)

      rows.each_with_object({}) do |row, result|
        next unless row.is_a?(Hash) && row.key?("version") && row.key?("checksum")

        result[row["version"].to_s] = row["checksum"].to_s
      end
    rescue Error
      {}
    end

    def load(path)
      if File.file?(path)
        raise ArgumentError, "not an .axql file: #{path}" unless path.end_with?(".axql")

        return [parse_file(path)]
      end
      raise ArgumentError, "migration directory not found: #{path}" unless Dir.exist?(path)

      Dir.glob(File.join(path, "*.axql")).sort.map { |file| parse_file(file) }
    end

    def status(path)
      completed = applied
      load(path).map { |migration| { migration: migration, applied: completed.key?(migration.version) } }
    end

    def up(path)
      completed = applied
      pending = load(path).reject do |migration|
        checksum = completed[migration.version]
        next false if checksum.nil?

        raise "migration checksum changed: #{migration.name}" unless checksum == migration.checksum

        true
      end
      return [] if pending.empty?

      ensure_table
      pending.each do |migration|
        @before_hooks.each { |hook| hook.call(migration) }
        @axon.query(migration.sql) unless migration.sql.strip.empty?
        @axon.query(
          "UPSERT #{@table}:v_#{migration.version} CONTENT { version: $v, name: $n, checksum: $c, applied_at: time::now() }",
          "v" => migration.version,
          "n" => migration.name,
          "c" => migration.checksum
        )
        @after_hooks.each { |hook| hook.call(migration) }
      end
      pending
    end

    private

    def parse_file(path)
      name = File.basename(path, ".axql")
      Migration.new(
        version: name.split("_", 2).first,
        name: name,
        path: path,
        checksum: Digest::SHA256.file(path).hexdigest,
        sql: File.read(path)
      )
    rescue Errno::ENOENT, Errno::EACCES => error
      raise "failed to read migration: #{path}: #{error.message}"
    end
  end
end
