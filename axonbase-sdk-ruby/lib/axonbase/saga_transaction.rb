# frozen_string_literal: true

module AxonBase
  class SagaTransaction
    attr_reader :saga_name, :correlation_id

    def initialize(axon, saga_name, correlation_id)
      @axon = axon
      @saga_name = saga_name
      @correlation_id = correlation_id
      @begun = false
      @finished = false
    end

    def is_begun?
      @begun
    end

    def is_finished?
      @finished
    end

    def begin
      raise Error, "Saga already begun: #{@correlation_id}" if @begun

      result = @axon.query("BEGIN SAGA #{escape(@saga_name)} WITH CORRELATION '#{escape(@correlation_id)}'")
      if result.is_a?(Hash) && result["status"] == "RUNNING"
        @begun = true
        return
      end

      raise Error, "Failed to begin saga: #{result}"
    end

    def step(axonql)
      assert_active
      @axon.let_var("saga_corr", @correlation_id)
      @axon.query(axonql)
    end

    def commit
      assert_active
      result = @axon.query("COMMIT SAGA #{escape(@saga_name)} WITH CORRELATION '#{escape(@correlation_id)}'")
      @finished = true
      raise Error, "Saga commit failed: #{result}" unless result.is_a?(Hash) && result["status"] == "COMMITTED"
    end

    def rollback
      return unless @begun && !@finished

      @axon.query("CANCEL SAGA #{escape(@saga_name)} WITH CORRELATION '#{escape(@correlation_id)}'")
      @finished = true
    end

    def describe
      @axon.query("SHOW SAGA TRANSACTION #{escape(@saga_name)} '#{escape(@correlation_id)}'")
    end

    private

    def assert_active
      raise Error, "Saga not begun" unless @begun
      raise Error, "Saga already finished" if @finished
    end

    def escape(value)
      value.gsub("\\") { "\\\\\\\\" }.gsub("'") { "''" }
    end
  end
end