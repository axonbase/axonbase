package com.axonbase.core.cluster;
import java.io.*; import java.util.*;
/** Codec do batch replicado para o payload de AppendEntries. */
final class RaftPayload {
 static byte[] encode(CommittedBatch batch){ try(var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes)){out.writeUTF(batch.transactionId());out.writeInt(batch.puts().size());for(var e:batch.puts().entrySet()){out.writeUTF(e.getKey());out.writeInt(e.getValue().length);out.write(e.getValue());}out.writeInt(batch.deletes().size());for(String key:batch.deletes())out.writeUTF(key);out.flush();return bytes.toByteArray();}catch(IOException e){throw new IllegalStateException(e);} }
 static CommittedBatch decode(byte[] raw){ try(var in=new DataInputStream(new ByteArrayInputStream(raw))){String tx=in.readUTF();int n=in.readInt();Map<String,byte[]> p=new LinkedHashMap<>();for(int i=0;i<n;i++){String k=in.readUTF();int len=in.readInt();byte[] v=in.readNBytes(len);if(v.length!=len)throw new EOFException();p.put(k,v);}int deletes=in.readInt();Set<String>d=new LinkedHashSet<>();for(int i=0;i<deletes;i++)d.add(in.readUTF());return new CommittedBatch(tx,p,d);}catch(IOException e){throw new IllegalArgumentException("payload Raft inválido",e);} }
}
