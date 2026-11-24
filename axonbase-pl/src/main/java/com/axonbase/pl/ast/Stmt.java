package com.axonbase.pl.ast;

import com.axonbase.pl.rt.Ctx;
import com.axonbase.pl.rt.Frame;

/** No de comando. Cada execucao conta um passo e avisa o hook de depuracao. */
public abstract class Stmt {
    public static final int NORMAL = 0;
    public static final int BREAK = 1;
    public static final int CONTINUE = 2;
    public static final int RETURN = 3;

    public int line;
    public int col;

    /** Executa o comando com contabilidade de passos e hook. */
    public final int run(Frame f) {
        f.cur = this;
        Ctx c = f.ctx;
        c.step();
        if (c.hook != null) {
            c.hook.onLine(f, line);
        }
        return exec(f);
    }

    protected abstract int exec(Frame f);

    /** Executa uma sequencia de comandos. */
    public static int runBlock(Stmt[] b, Frame f) {
        for (Stmt s : b) {
            int r = s.run(f);
            if (r != NORMAL) {
                return r;
            }
        }
        return NORMAL;
    }

    public final <T extends Stmt> T at(int line, int col) {
        this.line = line;
        this.col = col;
        @SuppressWarnings("unchecked")
        T t = (T) this;
        return t;
    }
}
