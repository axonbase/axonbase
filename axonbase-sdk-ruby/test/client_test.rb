# frozen_string_literal: true

require "minitest/autorun"
require "tmpdir"
require_relative "../lib/axonbase"

class FakeTransport
  attr_reader :sent
  def initialize(frames)
    @frames = frames
    @sent = []
  end
  def send(message); @sent << JSON.parse(message); end
  def receive; @frames.shift; end
  def close; end
end

class ClientTest < Minitest::Test
  def test_hello_use_and_query_envelopes
    transport = FakeTransport.new([
      '{"hello":{"protocol":1,"server":"test"}}',
      '{"id":1,"result":{"namespace":"app","database":"main"}}',
      '{"notification":{"id":"live-1","action":"CREATE","result":{}}}',
      '{"id":2,"result":[{"name":"Ana"}]}'
    ])
    client = AxonBase::Client.new(transport)
    assert_equal 1, client.hello["protocol"]
    client.use("app", "main")
    assert_equal "app", client.namespace
    assert_equal [{ "name" => "Ana" }], client.query("SELECT * FROM person", "when" => { "$datetime" => "2026-01-01T00:00:00Z" })
    assert_equal({ "id" => 1, "method" => "use", "params" => ["app", "main"], "version" => 1 }, transport.sent[0])
    assert_equal "query", transport.sent[1]["method"]
  end

  def test_not_leader_is_typed
    transport = FakeTransport.new(['{"hello":{"protocol":1}}', '{"id":1,"error":{"code":-32010,"message":"leader","leader":"n1","leader_address":"127.0.0.1:8000"}}'])
    error = assert_raises(AxonBase::NotLeaderError) { AxonBase::Client.new(transport).create("person", {}) }
    assert_equal "127.0.0.1:8000", error.leader_address
  end

  def test_relate_sends_correct_envelope
    transport = FakeTransport.new([
      '{"hello":{"protocol":1}}',
      '{"id":1,"result":{"id":"person:abc","in":"person:xyz"}}'
    ])
    client = AxonBase::Client.new(transport)
    result = client.relate("person:from", "knows", "person:to", { "since" => "2025" })
    assert_equal({ "id" => "person:abc", "in" => "person:xyz" }, result)
    assert_equal "relate", transport.sent[0]["method"]
    assert_equal ["person:from", "knows", "person:to", { "since" => "2025" }], transport.sent[0]["params"]
  end

  def test_relate_defaults_empty_data
    transport = FakeTransport.new([
      '{"hello":{"protocol":1}}',
      '{"id":1,"result":{}}'
    ])
    client = AxonBase::Client.new(transport)
    client.relate("a", "b", "c")
    assert_equal({}, transport.sent[0]["params"][3])
  end

  def test_transaction_rpcs
    transport = FakeTransport.new([
      '{"hello":{"protocol":1}}',
      '{"id":1,"result":null}',
      '{"id":2,"result":null}',
      '{"id":3,"result":null}'
    ])
    client = AxonBase::Client.new(transport)
    assert_nil client.begin
    assert_nil client.commit
    assert_nil client.cancel
    assert_equal "begin", transport.sent[0]["method"]
    assert_equal "commit", transport.sent[1]["method"]
    assert_equal "cancel", transport.sent[2]["method"]
  end

  def test_kv_get_set_del_scan
    transport = FakeTransport.new([
      '{"hello":{"protocol":1}}',
      '{"id":1,"result":"value1"}',
      '{"id":2,"result":null}',
      '{"id":3,"result":true}',
      '{"id":4,"result":[{"key":"k1","value":"v1"}]}'
    ])
    client = AxonBase::Client.new(transport)
    assert_equal "value1", client.kv_get("ns", "db", "key1")
    assert_nil client.kv_set("ns", "db", "key1", "value1", 300)
    assert_equal true, client.kv_del("ns", "db", "key1")
    assert_equal [{ "key" => "k1", "value" => "v1" }], client.kv_scan("ns", "db", "k")
    assert_equal "kv_get", transport.sent[0]["method"]
    assert_equal "kv_set", transport.sent[1]["method"]
    assert_equal "kv_del", transport.sent[2]["method"]
    assert_equal "kv_scan", transport.sent[3]["method"]
  end

  def test_kv_set_defaults_ttl_nil
    transport = FakeTransport.new([
      '{"hello":{"protocol":1}}',
      '{"id":1,"result":null}'
    ])
    client = AxonBase::Client.new(transport)
    client.kv_set("ns", "db", "k", "v")
    assert_equal ["k", "v", nil], transport.sent[0]["params"][2..]
  end

  def test_live_and_kill
    notifications = []
    transport = FakeTransport.new([
      '{"hello":{"protocol":1}}',
      '{"id":1,"result":"live-1"}',
      '{"notification":{"id":"live-1","action":"CREATE","result":{"id":"person:1"}}}',
      '{"id":2,"result":true}'
    ])
    client = AxonBase::Client.new(transport)
    handler = ->(id, action, result) { notifications << [id, action, result] }
    id = client.live("person", handler)
    assert_equal "live-1", id
    assert client.kill("live-1")
    assert_equal 1, notifications.size
    assert_equal ["live-1", "CREATE", { "id" => "person:1" }], notifications.first
    assert_equal "kill", transport.sent[1]["method"]
    assert_equal ["live-1"], transport.sent[1]["params"]
  end

  def test_live_dispatches_notifications
    notifications = []
    transport = FakeTransport.new([
      '{"hello":{"protocol":1}}',
      '{"id":1,"result":"live-1"}',
      '{"id":2,"result":"live-2"}',
      '{"notification":{"id":"live-1","action":"UPDATE","result":{"id":"person:1","name":"Bob"}}}',
      '{"id":3,"result":true}',
      '{"notification":{"id":"live-2","action":"DELETE","result":{"id":"person:2"}}}',
      '{"id":4,"result":true}'
    ])
    client = AxonBase::Client.new(transport)
    handler1 = ->(id, action, result) { notifications << ["h1", id, action, result] }
    handler2 = ->(id, action, result) { notifications << ["h2", id, action, result] }
    client.live("person", handler1)
    client.live("person", handler2)
    assert client.kill("live-1")
    assert client.kill("live-2")
    assert_includes notifications, ["h1", "live-1", "UPDATE", { "id" => "person:1", "name" => "Bob" }]
    assert_includes notifications, ["h2", "live-2", "DELETE", { "id" => "person:2" }]
  end

  def test_certificate_begin_parses_result
    transport = FakeTransport.new([
      '{"hello":{"protocol":1}}',
      '{"id":1,"result":{"id":"ch-1","challenge":"challenge-data","expires_at":"2026-12-31T23:59:59Z"}}'
    ])
    client = AxonBase::Client.new(transport)
    challenge = client.certificate_begin("icp-brasil")
    assert_equal "ch-1", challenge["id"]
    assert_equal "challenge-data", challenge["challenge"]
    assert_equal "2026-12-31T23:59:59Z", challenge["expires_at"]
    assert_equal "certificate.begin", transport.sent[0]["method"]
    assert_equal [{ "store" => "icp-brasil" }], transport.sent[0]["params"]
  end

  def test_certificate_complete_sends_completion
    transport = FakeTransport.new([
      '{"hello":{"protocol":1}}',
      '{"id":1,"result":"credential-token"}'
    ])
    client = AxonBase::Client.new(transport)
    completion = {
      "id" => "ch-1",
      "challenge" => "signed-data",
      "store" => "icp-brasil",
      "user" => "user@example.com",
      "chain" => ["cert1", "cert2"],
      "signature" => "sig-data"
    }
    credential = client.certificate_complete(completion)
    assert_equal "credential-token", credential
    assert_equal "certificate.complete", transport.sent[0]["method"]
    assert_equal [completion], transport.sent[0]["params"]
  end

  def test_let_var_and_unset
    transport = FakeTransport.new([
      '{"hello":{"protocol":1}}',
      '{"id":1,"result":null}',
      '{"id":2,"result":null}'
    ])
    client = AxonBase::Client.new(transport)
    client.let_var("saga_corr", "abc-123")
    client.unset("saga_corr")
    assert_equal "let", transport.sent[0]["method"]
    assert_equal ["saga_corr", "abc-123"], transport.sent[0]["params"]
    assert_equal "unset", transport.sent[1]["method"]
    assert_equal ["saga_corr"], transport.sent[1]["params"]
  end

  def test_let_var_strips_dollar_prefix
    transport = FakeTransport.new([
      '{"hello":{"protocol":1}}',
      '{"id":1,"result":null}',
      '{"id":2,"result":null}'
    ])
    client = AxonBase::Client.new(transport)
    client.let_var("$saga_corr", "val")
    assert_equal ["saga_corr", "val"], transport.sent[0]["params"]
    client.unset("$my_var")
    assert_equal ["my_var"], transport.sent[1]["params"]
  end

  def test_connect_passes_ssl_context_to_transport
    transport = FakeTransport.new(['{"hello":{"protocol":1}}'])
    client = AxonBase::Client.new(transport)
    assert_equal 1, client.hello["protocol"]
  end

  def test_migrator_applies_each_file_once_and_tracks_checksum
    Dir.mktmpdir("axonbase-ruby-migration") do |directory|
      file = File.join(directory, "001_create_person.axql")
      File.write(file, "DEFINE TABLE person SCHEMAFULL;")
      checksum = Digest::SHA256.file(file).hexdigest
      transport = FakeTransport.new([
        '{"hello":{"protocol":1}}',
        '{"id":1,"result":[]}',
        '{"id":2,"result":null}',
        '{"id":3,"result":null}',
        '{"id":4,"result":null}',
        %({"id":5,"result":[{"version":"001","checksum":"#{checksum}"}]}),
        %({"id":6,"result":[{"version":"001","checksum":"#{checksum}"}]})
      ])
      migrator = AxonBase::Migrator.new(AxonBase::Client.new(transport))
      before = []
      after = []
      migrator.on_before { |migration| before << migration.name }
      migrator.on_after { |migration| after << migration.name }

      assert_equal 1, migrator.up(directory).size
      assert_empty migrator.up(directory)
      assert migrator.status(directory).first[:applied]
      assert_equal ["001_create_person"], before
      assert_equal ["001_create_person"], after
      assert_match(/^DEFINE TABLE _migration/, transport.sent[1]["params"][0])
      assert_equal "DEFINE TABLE person SCHEMAFULL;", transport.sent[2]["params"][0]
      assert_equal checksum, transport.sent[3]["params"][1]["c"]
    end
  end
end

class SagaTransactionTest < Minitest::Test
  def test_saga_lifecycle
    transport = FakeTransport.new([
      '{"hello":{"protocol":1}}',
      '{"id":1,"result":{"status":"RUNNING"}}',         # BEGIN SAGA
      '{"id":2,"result":null}',                          # LET $saga_corr (via let_var)
      '{"id":3,"result":{"updated":1}}',                # step query
      '{"id":4,"result":{"status":"COMMITTED","steps":[]}}',  # describe
      '{"id":5,"result":{"status":"COMMITTED"}}',        # COMMIT SAGA
    ])
    client = AxonBase::Client.new(transport)
    saga = AxonBase::SagaTransaction.new(client, "pedido", "corr-123")

    assert_raises(AxonBase::Error) { saga.step("UPDATE orders:o1 SET total = 200") }
    saga.begin
    assert saga.is_begun?
    assert_equal({ "updated" => 1 }, saga.step("UPDATE orders:o1 SET total = 200"))
    assert_equal({ "status" => "COMMITTED", "steps" => [] }, saga.describe)
    saga.commit
    assert saga.is_finished?
    assert_raises(AxonBase::Error) { saga.commit }
  end

  def test_saga_rollback_is_idempotent
    transport = FakeTransport.new([
      '{"hello":{"protocol":1}}',
      '{"id":1,"result":{"status":"RUNNING"}}',         # BEGIN SAGA
      '{"id":2,"result":{"status":"CANCELLED"}}',        # CANCEL SAGA
    ])
    client = AxonBase::Client.new(transport)
    saga = AxonBase::SagaTransaction.new(client, "pedido", "corr-123")

    saga.rollback
    saga.begin
    saga.rollback
    saga.rollback
    assert saga.is_finished?
  end

  def test_saga_escape_special_chars
    transport = FakeTransport.new([
      '{"hello":{"protocol":1}}',
      '{"id":1,"result":{"status":"RUNNING"}}',         # BEGIN SAGA
      '{"id":2,"result":{"status":"COMMITTED"}}',        # COMMIT SAGA
    ])
    client = AxonBase::Client.new(transport)
    saga = AxonBase::SagaTransaction.new(client, "order's", "corr\\id'")
    saga.begin
    assert_includes transport.sent[0]["params"][0], "WITH CORRELATION 'corr\\\\\\\\id'''"
    saga.commit
    assert saga.is_finished?
  end
end

class SagaParticipantTransactionTest < Minitest::Test
  def test_participant_uses_local_transaction_rpcs
    transport = FakeTransport.new([
      '{"hello":{"protocol":1}}',
      '{"id":1,"result":null}',  # begin
      '{"id":2,"result":null}',  # let
      '{"id":3,"result":{"updated":1}}',  # step
      '{"id":4,"result":null}',  # commit
    ])
    client = AxonBase::Client.new(transport)
    participant = AxonBase::SagaParticipantTransaction.new(client, "corr-123")

    assert_raises(AxonBase::Error) { participant.step("UPDATE orders:o1 SET total = 200") }
    participant.begin
    participant.step("UPDATE orders:o1 SET total = 200")
    participant.commit
    assert participant.is_finished?

    methods = transport.sent.map { |s| s["method"] }
    assert_equal %w[begin let query commit], methods
    assert methods.none? { |m| m.include?("SAGA") }
  end

  def test_participant_rollback_is_idempotent
    transport = FakeTransport.new([
      '{"hello":{"protocol":1}}',
      '{"id":1,"result":null}',  # begin
      '{"id":2,"result":null}',  # let
      '{"id":3,"result":null}',  # cancel
    ])
    client = AxonBase::Client.new(transport)
    participant = AxonBase::SagaParticipantTransaction.new(client, "corr-123")

    participant.rollback
    participant.begin
    participant.rollback
    participant.rollback
    assert participant.is_finished?
  end
end
