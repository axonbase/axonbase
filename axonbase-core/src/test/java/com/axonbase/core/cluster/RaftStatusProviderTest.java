package com.axonbase.core.cluster;import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;
class RaftStatusProviderTest{@Test void readinessSegueQuorum(){var g=RaftGroup.inMemory("d","n1","n2","n3");var p=new RaftStatusProvider("n1","c",g);assertTrue(p.status().ready());g.stop("n2");g.stop("n3");assertFalse(p.status().ready());}}
