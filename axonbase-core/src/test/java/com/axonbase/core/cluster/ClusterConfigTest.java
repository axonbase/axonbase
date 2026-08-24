package com.axonbase.core.cluster;
import org.junit.jupiter.api.Test; import static org.junit.jupiter.api.Assertions.*;
class ClusterConfigTest { @Test void interpretaPeers(){var c=ClusterConfig.parse("n1","c1","n1:7000","n2:7000,n3:7000");assertEquals(2,c.peers().size());assertEquals(7000,c.advertiseAddress().getPort());} }
