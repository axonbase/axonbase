package com.axonbase.server;

import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Uma conexão HTTP persistente feita à mão, para o gerador do portão 2: o {@code HttpClient} do Java gasta no
 * gerador mais processador do que o servidor gasta para responder uma leitura por id, e a medida passa a ser a do
 * gerador. Atende só o que o {@code /sql} usa: {@code POST} e resposta com tamanho declarado ou em blocos.
 *
 * <p>Não é segura para várias threads: cada conexão tem a sua.</p>
 */
final class LeanHttpConnection implements Closeable {

    private static final int TIMEOUT_MILLIS = 30_000;

    /** O estado e o corpo de uma resposta. */
    record Response(int status, String body) {
    }

    private final Socket socket = new Socket();
    private final InputStream in;
    private final OutputStream out;
    private final String head;
    private final byte[] buffer = new byte[8_192];
    private int position;
    private int limit;
    private boolean closing;

    /**
     * @param url     endereço do nó, como {@code http://host:porta}
     * @param headers cabeçalhos de toda requisição, cada um terminado em {@code \r\n}
     */
    LeanHttpConnection(String url, String headers) throws IOException {
        URI target = URI.create(url);
        try {
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(TIMEOUT_MILLIS);
            socket.connect(new InetSocketAddress(target.getHost(), target.getPort()), 5_000);
            in = socket.getInputStream();
            out = socket.getOutputStream();
        } catch (IOException failure) {
            socket.close();
            throw failure;
        }
        head = " HTTP/1.1\r\nHost: " + target.getAuthority() + "\r\n" + headers + "Content-Length: ";
    }

    /** O servidor avisou que fecha a conexão depois da última resposta. */
    boolean closing() {
        return closing;
    }

    Response post(String path, String body) throws IOException {
        byte[] content = body.getBytes(StandardCharsets.UTF_8);
        byte[] prefix = ("POST " + path + head + content.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
        // Um só pacote: cabeçalho e corpo separados esperariam a confirmação atrasada do outro lado.
        byte[] request = new byte[prefix.length + content.length];
        System.arraycopy(prefix, 0, request, 0, prefix.length);
        System.arraycopy(content, 0, request, prefix.length, content.length);
        out.write(request);
        out.flush();
        return read();
    }

    private Response read() throws IOException {
        String statusLine = line();
        int status = Integer.parseInt(statusLine.substring(9, 12));
        int length = -1;
        boolean chunked = false;
        for (String header = line(); !header.isEmpty(); header = line()) {
            String lower = header.toLowerCase(Locale.ROOT);
            if (lower.startsWith("content-length:")) {
                length = Integer.parseInt(lower.substring("content-length:".length()).trim());
            } else if (lower.startsWith("transfer-encoding:")) {
                chunked = lower.contains("chunked");
            } else if (lower.startsWith("connection:")) {
                closing = lower.contains("close");
            }
        }
        if (chunked) {
            return new Response(status, chunks());
        }
        if (length < 0) {
            throw new IOException("resposta sem tamanho declarado: " + statusLine);
        }
        return new Response(status, new String(exactly(length), StandardCharsets.UTF_8));
    }

    private String chunks() throws IOException {
        java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
        while (true) {
            String size = line();
            int extension = size.indexOf(';');
            int bytes = Integer.parseInt((extension < 0 ? size : size.substring(0, extension)).trim(), 16);
            if (bytes == 0) {
                while (!line().isEmpty()) {
                    // cabeçalhos finais
                }
                return body.toString(StandardCharsets.UTF_8);
            }
            body.write(exactly(bytes));
            line();
        }
    }

    private void fill() throws IOException {
        limit = in.read(buffer);
        position = 0;
        if (limit <= 0) {
            limit = 0;
            throw new EOFException("o servidor fechou a conexão");
        }
    }

    private String line() throws IOException {
        StringBuilder text = new StringBuilder(64);
        while (true) {
            if (position == limit) {
                fill();
            }
            char next = (char) (buffer[position++] & 0xff);
            if (next == '\n') {
                int end = text.length();
                if (end > 0 && text.charAt(end - 1) == '\r') {
                    text.setLength(end - 1);
                }
                return text.toString();
            }
            text.append(next);
        }
    }

    private byte[] exactly(int count) throws IOException {
        byte[] bytes = new byte[count];
        int done = 0;
        while (done < count) {
            if (position == limit) {
                fill();
            }
            int step = Math.min(count - done, limit - position);
            System.arraycopy(buffer, position, bytes, done, step);
            position += step;
            done += step;
        }
        return bytes;
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
