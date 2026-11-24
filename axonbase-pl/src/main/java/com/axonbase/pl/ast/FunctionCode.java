package com.axonbase.pl.ast;

import java.util.HashMap;
import java.util.Map;

/** Codigo compilado de uma funcao, lambda, compreensao ou do corpo do modulo. */
public final class FunctionCode {
    public String name;
    public int line;
    public String doc;
    public boolean isModule;
    public Stmt[] body = new Stmt[0];
    public Expr lambdaBody;
    public int nslots;
    public String[] slotNames = new String[0];
    public int nPos;
    public String[] posNames = new String[0];
    public Expr[] defaults = new Expr[0];
    public String[] kwonlyNames = new String[0];
    public Expr[] kwDefaults = new Expr[0];
    public String varargs;
    public String varkw;
    public int varargsSlot = -1;
    public int varkwSlot = -1;
    public int[] kwonlySlots = new int[0];
    public int[] cellParams = new int[0];
    public int[] cellLocals = new int[0];
    public int[] freeSlots = new int[0];
    public int[] freeCapture = new int[0];
    public final Map<String, Integer> paramIndex = new HashMap<>();
    /** Verdadeiro se so tem parametros posicionais (caminho rapido). */
    public boolean simple;
}
