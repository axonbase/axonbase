package com.axonbase.pl.ast;

import com.axonbase.pl.rt.Frame;

/** f-string: concatenacao de pedacos literais e campos. */
public final class FString extends Expr {
    private final Expr[] parts;

    public FString(Expr[] parts) {
        this.parts = parts;
    }

    @Override
    public Object eval(Frame f) {
        StringBuilder sb = new StringBuilder();
        for (Expr p : parts) {
            sb.append((String) p.eval(f));
            if (sb.length() > f.ctx.maxStr) {
                f.ctx.checkStr(sb.length());
            }
        }
        return sb.toString();
    }
}
