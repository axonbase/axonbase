# frozen_string_literal: true

module AxonBase
  class Error < StandardError
    attr_reader :code
    def initialize(message, code = nil)
      super(message)
      @code = code
    end
  end

  class AuthError < Error; end
  class ConflictError < Error; end
  class NoQuorumError < Error; end
  class RateLimitError < Error; end

  class NotLeaderError < Error
    attr_reader :leader, :leader_address
    def initialize(message, leader = "", leader_address = "")
      super(message, -32_010)
      @leader = leader
      @leader_address = leader_address
    end
  end

  class ProtocolMismatchError < Error
    attr_reader :protocol, :server_version
    def initialize(message, protocol, server_version)
      super(message, -32_600)
      @protocol = protocol
      @server_version = server_version
    end
  end
end
