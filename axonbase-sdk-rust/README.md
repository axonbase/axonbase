# AxonBase Rust SDK

WebSocket JSON-RPC client for AxonBase.

```rust
use axonbase_sdk::{Axon, AxonOptions};

let axon = Axon::connect("ws://localhost:8000", AxonOptions::default()).await?;
```

See https://github.com/axonbase/axonbase for documentation and examples.
