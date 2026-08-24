package com.axonbase.core.cluster;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import static org.junit.jupiter.api.Assertions.assertTrue;
class TcpRaftServerTest {
 @Test void transmiteAppendEntriesPorTcp() throws Exception {
  try(var server=new TcpRaftServer(0, request -> new RaftWire.Response(request.term(), request.type()==RaftWire.APPEND, request.index()))) {
   var response=RaftWire.call(new InetSocketAddress("127.0.0.1",server.port()),new RaftWire.Request(RaftWire.APPEND,"app/main",4,"n1",8,8,new byte[0]),1000);
   assertTrue(response.accepted());
  }
 }
}
