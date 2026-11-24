package com.axonbase.pl.ast;

import com.axonbase.pl.rt.Frame;
import com.axonbase.pl.rt.NoneType;
import com.axonbase.pl.rt.Ops;

/** Comandos simples. */
public final class SimpleStmts {
    private SimpleStmts() {
    }

    /** Expressao usada como comando. */
    public static final class ExprStmt extends Stmt {
        public final Expr e;

        public ExprStmt(Expr e) {
            this.e = e;
        }

        @Override
        protected int exec(Frame f) {
            e.eval(f);
            return NORMAL;
        }
    }

    /** pass (e declaracoes global/nonlocal, sem efeito em execucao). */
    public static final class Pass extends Stmt {
        @Override
        protected int exec(Frame f) {
            return NORMAL;
        }
    }

    /** break. */
    public static final class Break extends Stmt {
        @Override
        protected int exec(Frame f) {
            return BREAK;
        }
    }

    /** continue. */
    public static final class Continue extends Stmt {
        @Override
        protected int exec(Frame f) {
            return CONTINUE;
        }
    }

    /** return. */
    public static final class Return extends Stmt {
        private final Expr e;

        public Return(Expr e) {
            this.e = e;
        }

        @Override
        protected int exec(Frame f) {
            f.ret = e == null ? NoneType.NONE : e.eval(f);
            return RETURN;
        }
    }

    /** Atribuicao simples ou encadeada. */
    public static final class Assign extends Stmt {
        private final Target[] targets;
        private final Expr value;

        public Assign(Target[] targets, Expr value) {
            this.targets = targets;
            this.value = value;
        }

        @Override
        protected int exec(Frame f) {
            Object v = value.eval(f);
            for (Target t : targets) {
                t.assign(f, v);
            }
            return NORMAL;
        }
    }

    /** Atribuicao aumentada. */
    public static final class AugAssign extends Stmt {
        private final Expr target;
        private final int op;
        private final Expr value;

        public AugAssign(Expr target, int op, Expr value) {
            this.target = target;
            this.op = op;
            this.value = value;
        }

        @Override
        protected int exec(Frame f) {
            if (target instanceof Subscript s) {
                Object o = s.obj.eval(f);
                Object k = s.idx.eval(f);
                Object cur = Ops.getitem(f, o, k);
                Ops.setitem(f, o, k, Ops.inplace(f, op, cur, value.eval(f)));
                return NORMAL;
            }
            Object cur = target.eval(f);
            Object rhs = value.eval(f);
            Object r;
            if (cur instanceof Long a && rhs instanceof Long b) {
                r = Ops.longOp(op, a, b);
            } else {
                r = Ops.inplace(f, op, cur, rhs);
            }
            ((Target) target).assign(f, r);
            return NORMAL;
        }
    }

    /** del. */
    public static final class Del extends Stmt {
        private final Target[] targets;

        public Del(Target[] targets) {
            this.targets = targets;
        }

        @Override
        protected int exec(Frame f) {
            for (Target t : targets) {
                t.delete(f);
            }
            return NORMAL;
        }
    }

    /** assert. */
    public static final class Assert extends Stmt {
        private final Expr cond;
        private final Expr msg;

        public Assert(Expr cond, Expr msg) {
            this.cond = cond;
            this.msg = msg;
        }

        @Override
        protected int exec(Frame f) {
            if (!Ops.truthy(cond.eval(f))) {
                Object[] args = msg == null ? new Object[0] : new Object[] {msg.eval(f)};
                throw new com.axonbase.pl.PlRuntimeError(
                        new com.axonbase.pl.rt.PyException(com.axonbase.pl.rt.Types.ASSERTION, args));
            }
            return NORMAL;
        }
    }
}
