package com.axonbase.core.cluster;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/** Estado Raft durável de un nó: termo, voto, último índice aplicado e peers. */
public final class RaftNodeState {
    private final Path file;
    private long term;
    private String votedFor = "";
    private long appliedIndex;
    private List<InetSocketAddress> peers = new ArrayList<>();
    public RaftNodeState(Path directory) {
        try { Files.createDirectories(directory); file=directory.resolve("raft.state"); load(); }
        catch(IOException e){ throw new UncheckedIOException(e); }
    }
    public synchronized long term(){ return term; }
    public synchronized String votedFor(){ return votedFor; }
    public synchronized long appliedIndex(){ return appliedIndex; }
    public synchronized List<InetSocketAddress> peers(){ return List.copyOf(peers); }
    public synchronized void update(long term,String votedFor,long appliedIndex){ this.term=term; this.votedFor=votedFor==null?"":votedFor; this.appliedIndex=appliedIndex; save(); }
    public synchronized void updatePeers(List<InetSocketAddress> peers){ this.peers=peers==null?new ArrayList<>():new ArrayList<>(peers); save(); }
    private void load() throws IOException { if(!Files.exists(file)) return; Properties p=new Properties(); try(var in=Files.newInputStream(file)){p.load(in);} term=Long.parseLong(p.getProperty("term","0")); votedFor=p.getProperty("voted_for",""); appliedIndex=Long.parseLong(p.getProperty("applied_index","0")); peers=parsePeers(p.getProperty("peers","")); }
    private void save(){ Properties p=new Properties(); p.setProperty("term",Long.toString(term)); p.setProperty("voted_for",votedFor); p.setProperty("applied_index",Long.toString(appliedIndex)); p.setProperty("peers",formatPeers(peers)); Path tmp=file.resolveSibling("raft.state.tmp"); try(var out=Files.newOutputStream(tmp)){p.store(out,"AxonBase Raft state"); out.flush();}catch(IOException e){throw new UncheckedIOException(e);} try{Files.move(tmp,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}catch(IOException e){throw new UncheckedIOException(e);} }
    private static List<InetSocketAddress> parsePeers(String raw){ List<InetSocketAddress> out=new ArrayList<>(); if(raw==null||raw.isBlank()) return out; for(String part: raw.split(",")){ part=part.trim(); int i=part.lastIndexOf(':'); if(i<1) continue; try{ out.add(new InetSocketAddress(part.substring(0,i),Integer.parseInt(part.substring(i+1)))); }catch(NumberFormatException ignored){} } return out; }
    private static String formatPeers(List<InetSocketAddress> peers){ StringBuilder sb=new StringBuilder(); for(InetSocketAddress p: peers){ if(sb.length()>0) sb.append(','); sb.append(p.getHostString()).append(':').append(p.getPort()); } return sb.toString(); }
}
