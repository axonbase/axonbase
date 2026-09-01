package com.axonbase.core.engine;

import java.util.List;

/** Server-side handler for administrative JKS registration commands. */
@FunctionalInterface
public interface JksRegistrar {
    void register(String name, String path, char[] password, String collector, List<String> oids);
}
