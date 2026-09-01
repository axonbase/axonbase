Gem::Specification.new do |spec|
  spec.name = "axonbase-sdk"
  spec.version = "0.1.0"
  spec.summary = "AxonBase WebSocket JSON-RPC client"
  spec.authors = ["AxonBase"]
  spec.license = "MIT"
  spec.files = Dir["lib/**/*.rb"]
  spec.required_ruby_version = ">= 2.6"
  spec.add_runtime_dependency "websocket-client-simple", "~> 0.7"
end
