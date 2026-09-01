# frozen_string_literal: true

module AxonBase
  class SagaParticipantTransaction
    attr_reader :correlation_id

    def initialize(axon, correlation_id)
      @axon = axon
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
      raise Error, "Transaction already begun: #{@correlation_id}" if @begun

      @axon.begin
      @axon.let_var("saga_corr", @correlation_id)
      @begun = true
    rescue StandardError
      @axon.cancel rescue nil
      raise
    end

    def step(axonql)
      assert_active
      @axon.query(axonql)
    end

    def commit
      assert_active
      @axon.commit
      @finished = true
    end

    def rollback
      return unless @begun && !@finished

      @axon.cancel
      @finished = true
    end

    private

    def assert_active
      raise Error, "Transaction not begun" unless @begun
      raise Error, "Transaction already finished" if @finished
    end
  end
end