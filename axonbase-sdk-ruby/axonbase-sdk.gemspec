Gem::Specification.new do |spec|
  spec.name = "axonbase-sdk"
  spec.version = "0.2.3"
  spec.summary = "AxonBase WebSocket JSON-RPC client"
  spec.authors = ["AxonBase"]
  spec.license = "MIT"
  spec.homepage = "https://github.com/axonbase/axonbase"
  spec.metadata["source_code_uri"] = "https://github.com/axonbase/axonbase"
  spec.metadata["bug_tracker_uri"] = "https://github.com/axonbase/axonbase/issues"
  spec.files = Dir["lib/**/*.rb"]
  spec.required_ruby_version = ">= 2.6"
  spec.add_runtime_dependency "websocket-client-simple", "~> 0.7"
end
