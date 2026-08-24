package com.axonbase.core.cluster;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

/** Estado Raft durável de um nó: termo, voto e último índice aplicado. */
public final class RaftNodeState {
    private final Path file;
    private long term;
    private String votedFor = "";
    private long appliedIndex;
    public RaftNodeState(Path directory) {
        try { Files.createDirectories(directory); file=directory.resolve("raft.state"); load(); }
        catch(IOException e){ throw new UncheckedIOException(e); }
    }
    public synchronized long term(){ return term; }
    public synchronized String votedFor(){ return votedFor; }
    public synchronized long appliedIndex(){ return appliedIndex; }
    public synchronized void update(long term,String votedFor,long appliedIndex){ this.term=term; this.votedFor=votedFor==null?"":votedFor; this.appliedIndex=appliedIndex; save(); }
    private void load() throws IOException { if(!Files.exists(file)) return; Properties p=new Properties(); try(var in=Files.newInputStream(file)){p.load(in);} term=Long.parseLong(p.getProperty("term","0")); votedFor=p.getProperty("voted_for",""); appliedIndex=Long.parseLong(p.getProperty("applied_index","0")); }
    private void save(){ Properties p=new Properties(); p.setProperty("term",Long.toString(term)); p.setProperty("voted_for",votedFor); p.setProperty("applied_index",Long.toString(appliedIndex)); Path tmp=file.resolveSibling("raft.state.tmp"); try(var out=Files.newOutputStream(tmp)){p.store(out,"AxonBase Raft state"); out.flush();}catch(IOException e){throw new UncheckedIOException(e);} try{Files.move(tmp,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}catch(IOException e){throw new UncheckedIOException(e);} }
}
