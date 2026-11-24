package com.axonbase.pl.ast;

import com.axonbase.pl.rt.Frame;
import com.axonbase.pl.rt.Ops;

/** Comparacao (possivelmente encadeada). */
public final class Compare extends Expr {
    public static final int IN = 6;
    public static final int NOT_IN = 7;
    public static final int IS = 8;
    public static final int IS_NOT = 9;

    private final Expr first;
    private final int[] ops;
    private final Expr[] rest;

    public Compare(Expr first, int[] ops, Expr[] rest) {
        this.first = first;
        this.ops = ops;
        this.rest = rest;
    }

    @Override
    public Object eval(Frame f) {
        Object left = first.eval(f);
        for (int i = 0; i < ops.length; i++) {
            Object right = rest[i].eval(f);
            boolean r;
            int op = ops[i];
            if (op <= Ops.NE) {
                r = Ops.compare(op, left, right);
            } else if (op == IN) {
                r = Ops.contains(f, right, left);
            } else if (op == NOT_IN) {
                r = !Ops.contains(f, right, left);
            } else if (op == IS) {
                r = Ops.is(left, right);
            } else {
                r = !Ops.is(left, right);
            }
            if (!r) {
                return Boolean.FALSE;
            }
            left = right;
        }
        return Boolean.TRUE;
    }
}
