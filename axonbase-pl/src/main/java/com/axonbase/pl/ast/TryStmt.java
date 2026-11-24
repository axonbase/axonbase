package com.axonbase.pl.ast;

import com.axonbase.pl.PlError;
import com.axonbase.pl.PlLimitError;
import com.axonbase.pl.PlRuntimeError;
import com.axonbase.pl.rt.Ctx;
import com.axonbase.pl.rt.Errors;
import com.axonbase.pl.rt.Frame;
import com.axonbase.pl.rt.NoneType;
import com.axonbase.pl.rt.PyException;
import com.axonbase.pl.rt.PyTuple;
import com.axonbase.pl.rt.PyType;
import com.axonbase.pl.rt.Types;

/** try / except / else / finally. */
public final class TryStmt extends Stmt {

    /** Clausula except. */
    public static final class Handler {
        public final Expr type;
        public final Name name;
        public final Stmt[] body;

        public Handler(Expr type, Name name, Stmt[] body) {
            this.type = type;
            this.name = name;
            this.body = body;
        }
    }

    private final Stmt[] body;
    private final Handler[] handlers;
    private final Stmt[] orelse;
    private final Stmt[] fin;

    public TryStmt(Stmt[] body, Handler[] handlers, Stmt[] orelse, Stmt[] fin) {
        this.body = body;
        this.handlers = handlers;
        this.orelse = orelse;
        this.fin = fin;
    }

    @Override
    protected int exec(Frame f) {
        if (fin.length == 0) {
            return main(f);
        }
        int r;
        try {
            r = main(f);
        } catch (PlLimitError e) {
            throw e;
        } catch (RuntimeException | StackOverflowError t) {
            if (t instanceof StackOverflowError) {
                throw t;
            }
            int fr = runBlock(fin, f);
            if (fr != NORMAL) {
                return fr;
            }
            throw t;
        }
        Object saved = f.ret;
        int fr = runBlock(fin, f);
        if (fr != NORMAL) {
            return fr;
        }
        f.ret = saved;
        return r;
    }

    private int main(Frame f) {
        int r;
        try {
            r = runBlock(body, f);
        } catch (PlLimitError e) {
            throw e;
        } catch (PlRuntimeError e) {
            Ctx c = f.ctx;
            if (!e.marcarNotificado() && c.hook != null) {
                c.hook.onException(f, e);
            }
            Handler h = find(f, e.excecao());
            if (h == null) {
                throw e;
            }
            return handle(f, h, e);
        }
        if (orelse.length > 0 && r == NORMAL) {
            return runBlock(orelse, f);
        }
        return r;
    }

    private Handler find(Frame f, PyException ex) {
        for (Handler h : handlers) {
            if (h.type == null) {
                return h;
            }
            Object t = h.type.eval(f);
            if (matches(t, ex)) {
                return h;
            }
        }
        return null;
    }

    private static boolean matches(Object t, PyException ex) {
        if (t instanceof PyType pt && pt.exc) {
            return ex.type.isSubtypeOf(pt);
        }
        if (t instanceof PyTuple tt) {
            for (Object o : tt.a) {
                if (matches(o, ex)) {
                    return true;
                }
            }
            return false;
        }
        throw Errors.type("so e possivel capturar classes de excecao em except");
    }

    private int handle(Frame f, Handler h, PlRuntimeError e) {
        Ctx c = f.ctx;
        PyException prev = c.handled;
        c.handled = e.excecao();
        try {
            if (h.name != null) {
                h.name.assign(f, e.excecao());
            }
            int r = runBlock(h.body, f);
            if (h.name != null) {
                h.name.unbind(f);
            }
            return r;
        } finally {
            c.handled = prev;
        }
    }

    /** Usado por raise e pelo corpo do except para validar valores. */
    static PyException toException(Object v) {
        if (v instanceof PyType t && t.exc) {
            return new PyException(t, new Object[0]);
        }
        if (v instanceof PyException e) {
            return e;
        }
        throw Errors.type("so e possivel levantar classes ou instancias de excecao");
    }

    /** raise. */
    public static final class Raise extends Stmt {
        private final Expr exc;
        private final Expr cause;
        private final boolean hasCause;

        public Raise(Expr exc, Expr cause, boolean hasCause) {
            this.exc = exc;
            this.cause = cause;
            this.hasCause = hasCause;
        }

        @Override
        protected int exec(Frame f) {
            Ctx c = f.ctx;
            if (exc == null) {
                if (c.handled == null) {
                    throw Errors.runtime("nao ha excecao ativa para relancar");
                }
                throw new PlRuntimeError(c.handled);
            }
            PyException ex = toException(exc.eval(f));
            if (hasCause) {
                Object cv = cause.eval(f);
                ex.cause = cv instanceof NoneType ? null : toException(cv);
            }
            if (c.handled != null && c.handled != ex && ex.context == null) {
                ex.context = c.handled;
            }
            PlError e = new PlRuntimeError(ex);
            throw e;
        }
    }

    static boolean unused() {
        return Types.EXCEPTION == null;
    }
}
