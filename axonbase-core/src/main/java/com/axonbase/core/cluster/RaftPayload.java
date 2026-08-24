package com.axonbase.core.cluster;
import java.io.*; import java.util.*;
/** Codec do batch replicado para o payload de AppendEntries. */
public final class RaftPayload {
 private RaftPayload(){}
 public static byte[] encode(CommittedBatch batch){ try(var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes)){out.writeUTF(batch.transactionId());out.writeInt(batch.puts().size());for(var e:batch.puts().entrySet()){out.writeUTF(e.getKey());out.writeInt(e.getValue().length);out.write(e.getValue());}out.writeInt(batch.deletes().size());for(String key:batch.deletes())out.writeUTF(key);out.flush();return bytes.toByteArray();}catch(IOException e){throw new IllegalStateException(e);} }
 public static CommittedBatch decode(byte[] raw){ try(var in=new DataInputStream(new ByteArrayInputStream(raw))){String tx=in.readUTF();int n=in.readInt();Map<String,byte[]> p=new LinkedHashMap<>();for(int i=0;i<n;i++){String k=in.readUTF();int len=in.readInt();byte[] v=in.readNBytes(len);if(v.length!=len)throw new EOFException();p.put(k,v);}int deletes=in.readInt();Set<String>d=new LinkedHashSet<>();for(int i=0;i<deletes;i++)d.add(in.readUTF());return new CommittedBatch(tx,p,d);}catch(IOException e){throw new IllegalArgumentException("payload Raft inválido",e);} }
 public static byte[] append(long previousIndex,long previousTerm,CommittedBatch batch){try(var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes)){out.writeInt(0x41585045);out.writeLong(previousIndex);out.writeLong(previousTerm);out.write(encode(batch));out.flush();return bytes.toByteArray();}catch(IOException e){throw new IllegalStateException(e);}}
 public static Append decodeAppend(byte[] raw){try(var in=new DataInputStream(new ByteArrayInputStream(raw))){if(in.readInt()!=0x41585045)throw new IllegalArgumentException("payload Append legado");long index=in.readLong(),term=in.readLong();return new Append(index,term,decode(in.readAllBytes()));}catch(IOException e){throw new IllegalArgumentException("append Raft inválido",e);}}
 public static byte[] vote(long lastIndex,long lastTerm){try(var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes)){out.writeLong(lastIndex);out.writeLong(lastTerm);out.flush();return bytes.toByteArray();}catch(IOException e){throw new IllegalStateException(e);}}
 public static Vote decodeVote(byte[] raw){try(var in=new DataInputStream(new ByteArrayInputStream(raw))){return new Vote(in.readLong(),in.readLong());}catch(IOException e){return new Vote(0,0);}}
 public record Append(long previousIndex,long previousTerm,CommittedBatch batch){}
 public record Vote(long lastIndex,long lastTerm){}
}
