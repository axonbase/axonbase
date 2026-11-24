package com.axonbase.pl.ast;

import com.axonbase.pl.rt.Frame;
import com.axonbase.pl.rt.Ops;
import com.axonbase.pl.rt.PyList;
import com.axonbase.pl.rt.PyRange;
import java.util.Iterator;

/** Comandos de controle de fluxo: if, while, for. */
public final class Flow {
    private Flow() {
    }

    /** if / elif / else. */
    public static final class If extends Stmt {
        private final Expr cond;
        private final Stmt[] body;
        private final Stmt[] orelse;

        public If(Expr cond, Stmt[] body, Stmt[] orelse) {
            this.cond = cond;
            this.body = body;
            this.orelse = orelse;
        }

        @Override
        protected int exec(Frame f) {
            if (Ops.truthy(cond.eval(f))) {
                return runBlock(body, f);
            }
            return orelse.length == 0 ? NORMAL : runBlock(orelse, f);
        }
    }

    /** while. */
    public static final class While extends Stmt {
        private final Expr cond;
        private final Stmt[] body;
        private final Stmt[] orelse;

        public While(Expr cond, Stmt[] body, Stmt[] orelse) {
            this.cond = cond;
            this.body = body;
            this.orelse = orelse;
        }

        @Override
        protected int exec(Frame f) {
            boolean broke = false;
            while (Ops.truthy(cond.eval(f))) {
                int c = runBlock(body, f);
                if (c == BREAK) {
                    broke = true;
                    break;
                }
                if (c == RETURN) {
                    return RETURN;
                }
            }
            if (!broke && orelse.length > 0) {
                return runBlock(orelse, f);
            }
            return NORMAL;
        }
    }

    /** for ... in. */
    public static final class For extends Stmt {
        private final Target target;
        private final Expr iter;
        private final Stmt[] body;
        private final Stmt[] orelse;

        public For(Target target, Expr iter, Stmt[] body, Stmt[] orelse) {
            this.target = target;
            this.iter = iter;
            this.body = body;
            this.orelse = orelse;
        }

        @Override
        protected int exec(Frame f) {
            Object itv = iter.eval(f);
            boolean broke = false;
            if (itv instanceof PyRange r) {
                long n = r.size();
                for (long i = 0; i < n; i++) {
                    target.assign(f, r.at(i));
                    int c = runBlock(body, f);
                    if (c == BREAK) {
                        broke = true;
                        break;
                    }
                    if (c == RETURN) {
                        return RETURN;
                    }
                }
            } else if (itv instanceof PyList l) {
                for (int i = 0; i < l.a.size(); i++) {
                    target.assign(f, l.a.get(i));
                    int c = runBlock(body, f);
                    if (c == BREAK) {
                        broke = true;
                        break;
                    }
                    if (c == RETURN) {
                        return RETURN;
                    }
                }
            } else {
                Iterator<Object> it = Ops.iter(f, itv);
                while (it.hasNext()) {
                    target.assign(f, it.next());
                    int c = runBlock(body, f);
                    if (c == BREAK) {
                        broke = true;
                        break;
                    }
                    if (c == RETURN) {
                        return RETURN;
                    }
                }
            }
            if (!broke && orelse.length > 0) {
                return runBlock(orelse, f);
            }
            return NORMAL;
        }
    }

    /** def. */
    public static final class FuncDef extends Stmt {
        private final Name name;
        private final MakeFunction fn;

        public FuncDef(Name name, MakeFunction fn) {
            this.name = name;
            this.fn = fn;
        }

        @Override
        protected int exec(Frame f) {
            name.assign(f, fn.eval(f));
            return NORMAL;
        }
    }
}
