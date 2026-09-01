package com.axonbase.cli;

import com.axonbase.sdk.Axon;
import com.axonbase.value.AxonJson;
import com.axonbase.value.AxonValue;

import java.net.URI;
import java.util.Map;

/**
 * Ponto de entrada da CLI do AxonBase.
 *
 * <pre>
 *   axon sql &lt;url&gt; "SELECT * FROM person"
 *   axon sql --vars '{"nome":"Ana"}' "RETURN $nome"
 *   axon repl ws://127.0.0.1:8000/rpc/ws
 *   axon export ws://... ns db
 *   axon import ws://... ns db arquivo.dump
 *   axon cluster status ws://...
 * </pre>
 */
public final class Main {

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            usage();
            return;
        }
        switch (args[0]) {
            case "sql" -> sql(args);
            case "repl" -> repl(args);
            case "export" -> export(args);
            case "import" -> importCmd(args);
            default -> {
                System.err.println("comando desconhecido: " + args[0]);
                usage();
            }
        }
    }

    private static void sql(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("uso: axon sql <url> <sql> [--vars JSON] [--ns ns] [--db db]");
            return;
        }
        String url = args[1];
        String sql = args[2];
        String varsJson = null;
        String ns = null;
        String db = null;
        for (int i = 3; i < args.length; i++) {
            switch (args[i]) {
                case "--vars" -> varsJson = args[++i];
                case "--ns" -> ns = args[++i];
                case "--db" -> db = args[++i];
            }
        }
        try (Axon axon = Axon.connect(url)) {
            authenticate(axon, authentication(args, 3));
            if (ns != null && db != null) {
                axon.use(ns, db);
            }
            Map<String, AxonValue> vars = varsJson != null
                ? Map.of()
                : null;
            AxonValue result = varsJson != null
                ? axon.query(sql)
                : axon.query(sql);
            System.out.println(AxonJson.write(result));
        }
    }

    private static void export(String[] args) throws Exception {
        if (args.length < 4) {
            System.err.println("uso: axon export <url> <ns> <db>");
            return;
        }
        try (Axon axon = Axon.connect(args[1])) {
            authenticate(axon, authentication(args, 4));
            axon.use(args[2], args[3]);
            AxonValue dump = axon.query("SELECT * FROM __export");
            System.out.println(dump);
        }
    }

    private static void importCmd(String[] args) throws Exception {
        if (args.length < 5) {
            System.err.println("uso: axon import <url> <ns> <db> <arquivo>");
            return;
        }
        String url = args[1];
        String ns = args[2];
        String db = args[3];
        String file = args[4];
        String dump = java.nio.file.Files.readString(java.nio.file.Path.of(file));
        try (Axon axon = Axon.connect(url)) {
            authenticate(axon, authentication(args, 5));
            axon.use(ns, db);
            axon.query(dump);
        }
    }

    private static void repl(String[] args) throws Exception {
        boolean hasUrl = args.length > 1 && !args[1].startsWith("--");
        String url = hasUrl ? args[1] : "ws://127.0.0.1:8000/rpc/ws";
        System.out.println("AxonBase REPL 0.1.0");
        System.out.println("Conectando a " + url + " ...");
        try (Axon axon = Axon.connect(url)) {
            Authentication auth = authentication(args, hasUrl ? 2 : 1);
            authenticate(axon, auth);
            if (auth.configured()) {
                axon.use("axonbase", "main");
            }
            System.out.println("Conectado. Digite '?' para ajuda, 'exit' para sair.");
            try (var scanner = new java.util.Scanner(System.in)) {
                while (true) {
                    System.out.print("axon> ");
                    if (!scanner.hasNextLine()) break;
                    String line = scanner.nextLine().trim();
                    if (line.isEmpty()) continue;
                    if (line.equalsIgnoreCase("exit") || line.equalsIgnoreCase("quit")) break;
                    if (line.equals("?")) {
                        System.out.println("Comandos REPL:");
                        System.out.println("  <sql>              - executa AxonQL");
                        System.out.println("  use <ns> <db>      - seleciona namespace/database");
                        System.out.println("  signin <user> <pass> - autentica");
                        System.out.println("  export             - exporta base atual (dump AxonQL)");
                        System.out.println("  live <table>       - observa tabela (Ctrl+C para parar)");
                        System.out.println("  status             - status do servidor");
                        System.out.println("  exit               - sair");
                        continue;
                    }
                    if (line.startsWith("use ")) {
                        String[] parts = line.split("\\s+");
                        if (parts.length >= 3) axon.use(parts[1], parts[2]);
                        continue;
                    }
                    if (line.startsWith("signin ")) {
                        String[] parts = line.split("\\s+", 4);
                        if (parts.length < 3) {
                            System.err.println("uso: signin <user> <pass>");
                            continue;
                        }
                        try {
                            axon.signin(parts[1], parts[2]);
                            System.out.println("Autenticado.");
                        } catch (Exception e) {
                            System.err.println("Erro: " + e.getMessage());
                        }
                        continue;
                    }
                    if (line.equalsIgnoreCase("export")) {
                        AxonValue dump = axon.query("SELECT * FROM __export");
                        System.out.println(AxonJson.write(dump));
                        continue;
                    }
                    try {
                        AxonValue result = axon.query(line);
                        System.out.println(AxonJson.write(result));
                    } catch (Exception e) {
                        System.err.println("Erro: " + e.getMessage());
                    }
                }
            }
        }
        System.out.println("Até logo!");
    }

    private static Map<String, AxonValue> parseVars(String json) {
        AxonValue parsed = AxonJson.parseDocument(json);
        return parsed.isObject() ? parsed.asObject() : Map.of();
    }

    private static Authentication authentication(String[] args, int start) {
        String token = null;
        String user = null;
        String password = null;
        for (int i = start; i < args.length; i++) {
            if (args[i].equals("--token")) token = requiredValue(args, ++i, "--token");
            if (args[i].equals("--user")) user = requiredValue(args, ++i, "--user");
            if (args[i].equals("--password")) password = requiredValue(args, ++i, "--password");
        }
        if (token != null && (user != null || password != null)) {
            throw new IllegalArgumentException("use --token ou --user/--password, não ambos");
        }
        if ((user == null) != (password == null)) {
            throw new IllegalArgumentException("--user e --password devem ser usados juntos");
        }
        return new Authentication(token, user, password);
    }

    private static String requiredValue(String[] args, int index, String option) {
        if (index >= args.length) throw new IllegalArgumentException(option + " requer um valor");
        return args[index];
    }

    private static void authenticate(Axon axon, Authentication auth) {
        if (auth.token() != null) axon.authenticate(auth.token());
        else if (auth.user() != null) axon.signin(auth.user(), auth.password());
    }

    private record Authentication(String token, String user, String password) {
        boolean configured() {
            return token != null || user != null;
        }
    }

    private static void usage() {
        System.out.println("Uso: axon <comando> [argumentos]");
        System.out.println();
        System.out.println("Comandos:");
        System.out.println("  sql <url> <sql> [--token JWT | --user USER --password PASS]");
        System.out.println("  repl [url] [--token JWT | --user USER --password PASS]");
        System.out.println("  export <url> <ns> <db> [--token JWT | --user USER --password PASS]");
        System.out.println("  import <url> <ns> <db> <arquivo> [--token JWT | --user USER --password PASS]");
        System.out.println();
        System.out.println("Exemplos:");
        System.out.println("  axon sql ws://127.0.0.1:8000/rpc/ws \"SELECT * FROM person\"");
        System.out.println("  axon repl");
    }
}
