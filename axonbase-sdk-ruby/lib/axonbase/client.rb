# frozen_string_literal: true

module AxonBase
  class Client
    attr_reader :namespace, :database, :hello

    def self.connect(url, ssl_context: nil)
      new(WebSocketTransport.new(url, ssl_context: ssl_context))
    end

    def initialize(transport)
      @transport = transport
      @next_id = 1
      @live_handlers = {}
      frame = decode(@transport.receive)
      raise Error, "expected hello as first WebSocket frame" unless frame["hello"].is_a?(Hash)
      @hello = frame["hello"]
    end

    def use(namespace, database)
      result = call("use", [namespace, database])
      @namespace = result.fetch("namespace", namespace)
      @database = result.fetch("database", database)
      result
    end

    def signin(user, pass, access = nil)
      credentials = { "user" => user, "pass" => pass }
      credentials["access"] = access unless access.nil?
      @token = call("signin", [credentials])
    end

    def authenticate(token)
      call("authenticate", [token])
      @token = token
      nil
    end

    def query(sql, vars = nil)
      call("query", vars.nil? ? [sql] : [sql, vars])
    end

    def select(target, where = nil); call("select", where.nil? ? [target] : [target, where]); end
    def create(target, data); call("create", [target, data]); end
    def insert(table, data); call("insert", [table, data]); end
    def update(target, data); call("update", [target, data]); end
    def upsert(target, data); call("upsert", [target, data]); end
    def delete(target, where = nil); call("delete", where.nil? ? [target] : [target, where]); end
    def ping; call("ping", []); end
    def version; call("version", []); end
    def close; @transport.close; end

    def relate(from, kind, to, data = {})
      call("relate", [from, kind, to, data])
    end

    def let_var(name, value)
      clean = name.start_with?("$") ? name[1..] : name
      call("let", [clean, value])
    end

    def unset(name)
      clean = name.start_with?("$") ? name[1..] : name
      call("unset", [clean])
    end

    def begin; call("begin", []); end
    def commit; call("commit", []); end
    def cancel; call("cancel", []); end

    def kv_get(ns, db, key); call("kv_get", [ns, db, key]); end
    def kv_set(ns, db, key, value, ttl = nil); call("kv_set", [ns, db, key, value, ttl]); end
    def kv_del(ns, db, key); call("kv_del", [ns, db, key]); end
    def kv_scan(ns, db, prefix); call("kv_scan", [ns, db, prefix]); end

    def live(table, handler, diff = false)
      id = call("live", [table, diff])
      @live_handlers[id] = handler
      id
    end

    def kill(id)
      ok = call("kill", [id])
      @live_handlers.delete(id) if ok
      ok
    end

    def certificate_begin(store)
      result = call("certificate.begin", [{ "store" => store }])
      {
        "id" => result["id"],
        "challenge" => result["challenge"],
        "expires_at" => result["expires_at"],
      }
    end

    def certificate_complete(completion_hash)
      call("certificate.complete", [completion_hash])
    end

    private

    def call(method, params)
      id = @next_id
      @next_id += 1
      @transport.send(JSON.generate("id" => id, "method" => method, "params" => params, "version" => PROTOCOL_VERSION))
      loop do
        response = decode(@transport.receive)
        if response.key?("notification")
          n = response["notification"]
          handler = @live_handlers[n["id"]]
          handler&.call(n["id"], n["action"], n["result"])
          next
        end
        next if response["id"] != id
        raise rpc_error(response["error"]) if response.key?("error")
        return response["result"]
      end
    end

    def decode(frame)
      JSON.parse(frame)
    rescue JSON::ParserError
      raise Error, "invalid JSON-RPC frame"
    end

    def rpc_error(error)
      code, message = error.fetch("code", 0), error.fetch("message", "RPC error")
      case code
      when -32_002 then AuthError.new(message, code)
      when -32_009 then ConflictError.new(message, code)
      when -32_010 then NotLeaderError.new(message, error.fetch("leader", ""), error.fetch("leader_address", ""))
      when -32_011 then NoQuorumError.new(message, code)
      when -32_029 then RateLimitError.new(message, code)
      when -32_600
        return ProtocolMismatchError.new(message, error.fetch("protocol", 0), error.fetch("version", 0)) if error["kind"] == "PROTOCOL_MISMATCH"
        Error.new(message, code)
      else Error.new(message, code)
      end
    end
  end
end
