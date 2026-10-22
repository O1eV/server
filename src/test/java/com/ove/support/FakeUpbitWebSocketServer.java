package com.ove.support;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 테스트용 가짜 업비트 WebSocket 서버(RFC 6455를 필요한 만큼만 구현). 실제 업비트에는 연결하지 않는다.
 * 별도 라이브러리 없이 ServerSocket으로 핸드셰이크를 하고, 서버→클라이언트 프레임을 직접 만들어 보낸다.
 */
public class FakeUpbitWebSocketServer implements AutoCloseable {

    private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private final ServerSocket serverSocket;
    private final List<Connection> connections = new CopyOnWriteArrayList<>();
    private volatile boolean respondToPings = true;

    public FakeUpbitWebSocketServer() throws IOException {
        this.serverSocket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread acceptor = new Thread(this::acceptLoop, "fake-upbit-acceptor");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    public String url() {
        return "ws://127.0.0.1:" + serverSocket.getLocalPort();
    }

    public void setRespondToPings(boolean respondToPings) {
        this.respondToPings = respondToPings;
    }

    public int connectionCount() {
        return connections.size();
    }

    /** n번째 연결(1부터)의 핸드셰이크가 끝날 때까지 기다린다. */
    public Connection awaitConnection(int n) {
        if (!Await.until(() -> connections.size() >= n && connections.get(n - 1).handshakeDone, Duration.ofSeconds(5))) {
            throw new AssertionError(n + "번째 연결이 맺어지지 않음");
        }
        return connections.get(n - 1);
    }

    @Override
    public void close() throws IOException {
        serverSocket.close();
        connections.forEach(Connection::close);
    }

    private void acceptLoop() {
        while (!serverSocket.isClosed()) {
            try {
                Socket socket = serverSocket.accept();
                Connection connection = new Connection(socket);
                connections.add(connection);
                Thread thread = new Thread(connection::run, "fake-upbit-connection");
                thread.setDaemon(true);
                thread.start();
            } catch (IOException e) {
                return;
            }
        }
    }

    public final class Connection {

        private final Socket socket;
        private final List<String> receivedTexts = new CopyOnWriteArrayList<>();
        private final AtomicInteger pings = new AtomicInteger();
        private volatile boolean handshakeDone;

        private Connection(Socket socket) {
            this.socket = socket;
        }

        /** 클라이언트가 보낸 텍스트 프레임(구독 요청 등) */
        public List<String> receivedTexts() {
            return receivedTexts;
        }

        public int pingCount() {
            return pings.get();
        }

        public void sendBinary(String message) throws IOException {
            writeFrame(0x2, true, message.getBytes(StandardCharsets.UTF_8));
        }

        public void sendText(String message) throws IOException {
            writeFrame(0x1, true, message.getBytes(StandardCharsets.UTF_8));
        }

        /** 한 메시지를 binary 프레임 + continuation 프레임으로 쪼개서 보낸다. */
        public void sendBinaryFragmented(String message) throws IOException {
            byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
            int half = bytes.length / 2;
            writeFrame(0x2, false, java.util.Arrays.copyOfRange(bytes, 0, half));
            writeFrame(0x0, true, java.util.Arrays.copyOfRange(bytes, half, bytes.length));
        }

        /** 서버 쪽에서 연결을 갑자기 끊는다. */
        public void close() {
            try {
                socket.close();
            } catch (IOException ignored) {
                // 이미 닫힌 경우
            }
        }

        private synchronized void writeFrame(int opcode, boolean fin, byte[] payload) throws IOException {
            OutputStream out = socket.getOutputStream();
            out.write((fin ? 0x80 : 0x00) | opcode);
            if (payload.length <= 125) {
                out.write(payload.length);
            } else if (payload.length <= 0xFFFF) {
                out.write(126);
                out.write(payload.length >> 8);
                out.write(payload.length & 0xFF);
            } else {
                out.write(127);
                for (int shift = 56; shift >= 0; shift -= 8) {
                    out.write((int) ((long) payload.length >> shift) & 0xFF);
                }
            }
            out.write(payload);
            out.flush();
        }

        private void run() {
            try {
                handshake();
                readFrames();
            } catch (IOException ignored) {
                // 연결이 끊긴 경우
            } finally {
                close();
            }
        }

        private void handshake() throws IOException {
            InputStream in = socket.getInputStream();
            ByteArrayOutputStream header = new ByteArrayOutputStream();
            int previous = 0;
            int matched = 0;
            while (matched < 4) {
                int b = in.read();
                if (b < 0) {
                    throw new EOFException();
                }
                header.write(b);
                // "\r\n\r\n"을 만나면 HTTP 헤더 끝
                matched = (b == (matched % 2 == 0 ? '\r' : '\n')) ? matched + 1 : (b == '\r' ? 1 : 0);
                previous = b;
            }
            String request = header.toString(StandardCharsets.UTF_8);
            String key = request.lines().filter(line -> line.toLowerCase().startsWith("sec-websocket-key:"))
                    .map(line -> line.substring(line.indexOf(':') + 1).trim()).findFirst()
                    .orElseThrow(() -> new IOException("Sec-WebSocket-Key 없음"));
            String accept;
            try {
                accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
                        .digest((key + GUID).getBytes(StandardCharsets.UTF_8)));
            } catch (java.security.NoSuchAlgorithmException e) {
                throw new IOException(e);
            }
            String response = "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                    + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n";
            socket.getOutputStream().write(response.getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            handshakeDone = true;
        }

        private void readFrames() throws IOException {
            InputStream in = socket.getInputStream();
            while (true) {
                int first = in.read();
                if (first < 0) {
                    return;
                }
                int opcode = first & 0x0F;
                int second = in.read();
                boolean masked = (second & 0x80) != 0;
                long length = second & 0x7F;
                if (length == 126) {
                    length = (in.read() << 8) | in.read();
                } else if (length == 127) {
                    length = 0;
                    for (int i = 0; i < 8; i++) {
                        length = (length << 8) | in.read();
                    }
                }
                byte[] mask = new byte[4];
                if (masked) {
                    in.readNBytes(mask, 0, 4);
                }
                byte[] payload = in.readNBytes((int) length);
                if (masked) {
                    for (int i = 0; i < payload.length; i++) {
                        payload[i] ^= mask[i % 4];
                    }
                }
                if (opcode == 0x1) {
                    receivedTexts.add(new String(payload, StandardCharsets.UTF_8));
                } else if (opcode == 0x9) {
                    pings.incrementAndGet();
                    if (respondToPings) {
                        writeFrame(0xA, true, payload);
                    }
                } else if (opcode == 0x8) {
                    return;
                }
            }
        }
    }
}
