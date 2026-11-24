package com.axonbase.pl.ast;

import com.axonbase.pl.rt.Attrs;
import com.axonbase.pl.rt.Errors;
import com.axonbase.pl.rt.Frame;
import com.axonbase.pl.rt.Modules;
import com.axonbase.pl.rt.Types;

/** import e from-import. */
public final class ImportStmts {
    private ImportStmts() {
    }

    /** import modulo [as nome]. */
    public static final class Import extends Stmt {
        private final String module;
        private final Name target;

        public Import(String module, Name target) {
            this.module = module;
            this.target = target;
        }

        @Override
        protected int exec(Frame f) {
            target.assign(f, Modules.importModule(f, module));
            return NORMAL;
        }
    }

    /** from modulo import a [as b], .... */
    public static final class ImportFrom extends Stmt {
        private final String module;
        private final String[] names;
        private final Name[] targets;

        public ImportFrom(String module, String[] names, Name[] targets) {
            this.module = module;
            this.names = names;
            this.targets = targets;
        }

        @Override
        protected int exec(Frame f) {
            Object m = Modules.importModule(f, module);
            for (int i = 0; i < names.length; i++) {
                Object v;
                try {
                    v = Attrs.getattr(f, m, names[i]);
                } catch (com.axonbase.pl.PlRuntimeError e) {
                    if (e.excecao().type.isSubtypeOf(Types.ATTRIBUTE_ERROR)) {
                        throw Errors.make(Types.IMPORT_ERROR,
                                "nao foi possivel importar o nome '" + names[i] + "' de '" + module + "'");
                    }
                    throw e;
                }
                targets[i].assign(f, v);
            }
            return NORMAL;
        }
    }
}
