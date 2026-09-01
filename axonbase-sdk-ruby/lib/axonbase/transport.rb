# frozen_string_literal: true

module AxonBase
  class WebSocketTransport
    def initialize(url, ssl_context: nil)
      require "websocket-client-simple"
      @messages = Queue.new
      options = ssl_context ? { tls: ssl_context } : {}
      @socket = WebSocket::Client::Simple.connect(url, options)
      @socket.on(:message) { |message| @messages << message.data }
      @socket.on(:error) { |error| @messages << error }
    end

    def send(message)
      @socket.send(message)
    end

    def receive
      message = @messages.pop
      raise message if message.is_a?(Exception)
      message
    end

    def close
      @socket.close
    end
  end
end
