package com.axonbase.core;

import com.axonbase.core.storage.KvBackend;
import com.axonbase.core.storage.MemoryBackend;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/** Backend de teste que deixa intercalar uma ação em volta de uma leitura ou remoção, como faria outra thread. */
final class HookBackend implements KvBackend {

    final MemoryBackend inner;
    volatile Consumer<String> beforeGet = key -> {
    };
    volatile Consumer<String> afterGet = key -> {
    };
    volatile Consumer<String> beforeDelete = key -> {
    };

    HookBackend() {
        this(new MemoryBackend());
    }

    HookBackend(MemoryBackend inner) {
        this.inner = inner;
    }

    @Override
    public Optional<byte[]> get(String key) {
        beforeGet.accept(key);
        Optional<byte[]> value = inner.get(key);
        afterGet.accept(key);
        return value;
    }

    @Override
    public void put(String key, byte[] value) {
        inner.put(key, value);
    }

    @Override
    public boolean putIfAbsent(String key, byte[] value) {
        return inner.putIfAbsent(key, value);
    }

    @Override
    public boolean delete(String key) {
        beforeDelete.accept(key);
        return inner.delete(key);
    }

    @Override
    public List<String> keysWithPrefix(String prefix) {
        return inner.keysWithPrefix(prefix);
    }
}
