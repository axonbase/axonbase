package com.axonbase.pl.rt;

import com.axonbase.pl.ast.Call;
import java.util.Iterator;
import java.util.NoSuchElementException;

/** Iteradores preguicosos usados por enumerate, zip, map, filter e reversed. */
public final class Seqs {
    private Seqs() {
    }

    public static PyIterator enumerate(Frame f, Object src, long start) {
        Iterator<Object> it = Ops.iter(f, src);
        return new PyIterator(new Iterator<>() {
            long i = start;

            @Override
            public boolean hasNext() {
                return it.hasNext();
            }

            @Override
            public Object next() {
                return new PyTuple(new Object[] {i++, it.next()});
            }
        });
    }

    public static PyIterator zip(Frame f, Object[] srcs) {
        Iterator<Object>[] its = newArray(srcs.length);
        for (int i = 0; i < srcs.length; i++) {
            its[i] = Ops.iter(f, srcs[i]);
        }
        return new PyIterator(new Iterator<>() {
            @Override
            public boolean hasNext() {
                if (its.length == 0) {
                    return false;
                }
                for (Iterator<Object> it : its) {
                    if (!it.hasNext()) {
                        return false;
                    }
                }
                return true;
            }

            @Override
            public Object next() {
                Object[] r = new Object[its.length];
                for (int i = 0; i < r.length; i++) {
                    r[i] = its[i].next();
                }
                return new PyTuple(r);
            }
        });
    }

    @SuppressWarnings("unchecked")
    private static Iterator<Object>[] newArray(int n) {
        return (Iterator<Object>[]) new Iterator<?>[n];
    }

    public static PyIterator map(Frame f, Object fn, Object[] srcs) {
        Iterator<Object>[] its = newArray(srcs.length);
        for (int i = 0; i < srcs.length; i++) {
            its[i] = Ops.iter(f, srcs[i]);
        }
        return new PyIterator(new Iterator<>() {
            @Override
            public boolean hasNext() {
                for (Iterator<Object> it : its) {
                    if (!it.hasNext()) {
                        return false;
                    }
                }
                return its.length > 0;
            }

            @Override
            public Object next() {
                Object[] r = new Object[its.length];
                for (int i = 0; i < r.length; i++) {
                    r[i] = its[i].next();
                }
                f.ctx.step();
                return Call.invoke(f, fn, r, null);
            }
        });
    }

    public static PyIterator filter(Frame f, Object fn, Object src) {
        Iterator<Object> it = Ops.iter(f, src);
        return new PyIterator(new Iterator<>() {
            Object nxt;
            boolean has;

            private void advance() {
                while (!has && it.hasNext()) {
                    Object v = it.next();
                    f.ctx.step();
                    boolean keep = fn instanceof NoneType ? Ops.truthy(v)
                            : Ops.truthy(Call.invoke(f, fn, new Object[] {v}, null));
                    if (keep) {
                        nxt = v;
                        has = true;
                    }
                }
            }

            @Override
            public boolean hasNext() {
                advance();
                return has;
            }

            @Override
            public Object next() {
                advance();
                if (!has) {
                    throw new NoSuchElementException();
                }
                has = false;
                return nxt;
            }
        });
    }

    public static PyIterator reversed(Frame f, Object src) {
        final int n;
        final java.util.function.IntFunction<Object> at;
        if (src instanceof PyList l) {
            n = l.a.size();
            at = i -> l.a.get(i);
        } else if (src instanceof PyTuple t) {
            n = t.a.length;
            at = i -> t.a[i];
        } else if (src instanceof String s) {
            n = s.length();
            at = i -> String.valueOf(s.charAt(i));
        } else if (src instanceof PyRange r) {
            n = (int) r.size();
            at = i -> r.at(i);
        } else {
            throw Errors.type("o objeto do tipo '" + Types.nomeDe(src) + "' nao e reversivel");
        }
        return new PyIterator(new Iterator<>() {
            int i = n - 1;

            @Override
            public boolean hasNext() {
                return i >= 0;
            }

            @Override
            public Object next() {
                return at.apply(i--);
            }
        });
    }
}
