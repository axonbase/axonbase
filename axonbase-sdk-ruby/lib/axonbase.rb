# frozen_string_literal: true

require "json"
require "thread"
require_relative "axonbase/errors"
require_relative "axonbase/transport"
require_relative "axonbase/client"
require_relative "axonbase/migration"
require_relative "axonbase/saga_transaction"
require_relative "axonbase/saga_participant_transaction"

module AxonBase
  PROTOCOL_VERSION = 1
end
