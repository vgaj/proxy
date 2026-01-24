package com.github.vgaj.proxy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;

public class MobileProxy {

    private static final String SERVER_HOST = "localhost";
    private static final int SERVER_PORT = 9999;

    private record Connection(SelectionKey peerKey, ByteBuffer buffer) {
    }

    private static final int MAX_IDLE = 5;
    private static final int MAX_TOTAL = 100;
    private static final java.util.Set<SocketChannel> tunnels = new java.util.HashSet<>();

    public static void main(String[] args) throws Exception {
        Selector selector = Selector.open();

        System.out.println("MobileProxy started. Targetting " + SERVER_HOST + ":" + SERVER_PORT);

        // Initial pool population
        maintainConnectionPool(selector);

        while (true) {
            selector.select();
            Iterator<SelectionKey> keys = selector.selectedKeys().iterator();

            while (keys.hasNext()) {
                SelectionKey key = keys.next();
                keys.remove();

                if (!key.isValid())
                    continue;

                try {
                    if (key.isConnectable()) {
                        handleConnect(key, selector);
                        // A connection succeeded, check if we need to re-balance anything (unlikely,
                        // but good practice)
                        maintainConnectionPool(selector);
                    } else if (key.isReadable()) {
                        handleRead(key, selector);
                    } else if (key.isWritable()) {
                        handleWrite(key);
                    }
                } catch (Exception e) {
                    System.out.println("Error: " + e.getMessage());
                    close(key, selector);
                }
            }
        }
    }

    private static void maintainConnectionPool(Selector selector) throws IOException {
        int idleOrPending = 0;
        Iterator<SocketChannel> it = tunnels.iterator();
        while (it.hasNext()) {
            SocketChannel sc = it.next();
            if (!sc.isOpen()) {
                it.remove();
                continue;
            }
            SelectionKey key = sc.keyFor(selector);
            if (key != null && key.isValid()) {
                // It is idle or pending if it has no attachment (meaning not yet paired to a
                // target)
                // AND it is either connectable (pending) or readable (idle waiting for request)
                if (key.attachment() == null) {
                    idleOrPending++;
                }
            } else {
                // Invalid key but open channel? Should verify.
                // If key is cancelled, it's effectively dead for us.
                it.remove();
            }
        }

        int needed = MAX_IDLE - idleOrPending;
        int canCreate = MAX_TOTAL - tunnels.size();
        int toCreate = Math.min(needed, canCreate);

        for (int i = 0; i < toCreate; i++) {
            initiateConnection(selector);
        }

        if (toCreate > 0) {
            System.out.println("Pool maintenance: Created " + toCreate + " new connections. Total: " + tunnels.size()
                    + ", Idle/Pending: " + (idleOrPending + toCreate));
        }
    }

    private static void initiateConnection(Selector selector) throws IOException {
        SocketChannel tunnel = SocketChannel.open();
        tunnel.configureBlocking(false);
        tunnel.connect(new InetSocketAddress(SERVER_HOST, SERVER_PORT));
        tunnel.register(selector, SelectionKey.OP_CONNECT);
        tunnels.add(tunnel);
    }

    private static void handleConnect(SelectionKey key, Selector selector) throws IOException {
        SocketChannel channel = (SocketChannel) key.channel();
        if (channel.finishConnect()) {
            System.out.println("Connected to ProxyServer. Waiting for traffic...");
            key.interestOps(SelectionKey.OP_READ);
        }
    }

    private static void handleRead(SelectionKey key, Selector selector) throws IOException {
        SocketChannel channel = (SocketChannel) key.channel();
        Object attachment = key.attachment();

        // If no attachment, this is the Tunnel reading from Server (Browser request)
        if (attachment == null) {
            ByteBuffer buf = ByteBuffer.allocate(8192);
            int n = channel.read(buf);
            if (n == -1) {
                System.out.println("ProxyServer closed connection.");
                close(key, selector);
                return;
            }

            buf.flip();
            // We have the initial request in buf. Parse it.
            processInitialRequest(key, selector, buf);
            // The connection is now active (paired), so we need to replenish the idle pool
            maintainConnectionPool(selector);
            return;
        }

        // Normal forwarding
        Connection conn = (Connection) attachment;
        int read;
        try {
            read = channel.read(conn.buffer);
        } catch (IOException e) {
            read = -1;
        }
        if (read == -1) {
            close(key, selector);
            close(conn.peerKey(), selector);
            return;
        }

        conn.buffer.flip();
        writeToPeer(key, conn);
    }

    private static void processInitialRequest(SelectionKey tunnelKey, Selector selector, ByteBuffer buffer)
            throws IOException {
        String request = new String(buffer.array(), 0, buffer.limit(), StandardCharsets.ISO_8859_1);

        String host = null;
        int port = 80;
        boolean isConnect = request.startsWith("CONNECT");
        int headerEnd = -1;

        if (isConnect) {
            String[] parts = request.split(" ");
            if (parts.length > 1) {
                String[] hp = parts[1].split(":");
                host = hp[0];
                port = Integer.parseInt(hp[1]);
            }
            // Identify where the headers end to handle pipelined data
            headerEnd = request.indexOf("\r\n\r\n");
            if (headerEnd != -1) {
                headerEnd += 4;
            } else {
                headerEnd = buffer.limit();
            }
        } else {
            for (String line : request.split("\r\n")) {
                if (line.regionMatches(true, 0, "Host:", 0, 5)) {
                    host = line.substring(5).trim();
                    if (host.contains(":")) {
                        String[] hp = host.split(":");
                        host = hp[0];
                        port = Integer.parseInt(hp[1]);
                    }
                    break;
                }
            }
            // For GET, we want to forward everything
            headerEnd = 0;
        }

        if (host == null) {
            System.err.println("Could not parse host from request");
            tunnelKey.channel().close();
            return;
        }

        System.out.println("Connecting to target: " + host + ":" + port);

        SocketChannel target = SocketChannel.open();
        try {
            target.configureBlocking(true);
            target.connect(new InetSocketAddress(host, port));
            target.configureBlocking(false);
        } catch (IOException e) {
            System.err.println("Failed to connect: " + e.getMessage());
            tunnelKey.channel().close();
            return;
        }

        registerTarget(selector, tunnelKey, target, buffer, headerEnd, isConnect);
    }

    private static void registerTarget(Selector selector, SelectionKey tunnelKey, SocketChannel target,
            ByteBuffer buffer, int dataStart, boolean isConnect) throws IOException {
        SelectionKey targetKey = target.register(selector, SelectionKey.OP_READ);

        // c1: Attached to tunnelKey. Reads from Tunnel, Writes to Target.
        Connection c1 = new Connection(targetKey, ByteBuffer.allocateDirect(8192));

        // c2: Attached to targetKey. Reads from Target, Writes to Tunnel.
        Connection c2 = new Connection(tunnelKey, ByteBuffer.allocateDirect(8192));

        tunnelKey.attach(c1);
        targetKey.attach(c2);

        // Handle leftover data from the initial buffer
        if (dataStart < buffer.limit()) {
            buffer.position(dataStart);
            c1.buffer().put(buffer);
            c1.buffer().flip();

            // Try explicit first write
            target.write(c1.buffer());

            if (c1.buffer().hasRemaining()) {
                targetKey.interestOps(targetKey.interestOps() | SelectionKey.OP_WRITE);
                tunnelKey.interestOps(tunnelKey.interestOps() & ~SelectionKey.OP_READ);
            } else {
                c1.buffer().clear();
            }
        }

        if (isConnect) {
            // Inject 200 OK into c2 (simulating data from Target -> Tunnel)
            String response = "HTTP/1.1 200 Connection Established\r\n\r\n";
            c2.buffer().put(response.getBytes(StandardCharsets.ISO_8859_1));
            c2.buffer().flip();

            // Try explicit first write to tunnel
            ((SocketChannel) tunnelKey.channel()).write(c2.buffer());

            if (c2.buffer().hasRemaining()) {
                tunnelKey.interestOps(tunnelKey.interestOps() | SelectionKey.OP_WRITE);
                targetKey.interestOps(targetKey.interestOps() & ~SelectionKey.OP_READ);
            } else {
                c2.buffer().clear();
            }
        }
    }

    private static void handleWrite(SelectionKey key) throws IOException {
        Connection conn = (Connection) key.attachment();
        SelectionKey sourceKey = conn.peerKey;
        Connection sourceConn = (Connection) sourceKey.attachment();
        writeToPeer(sourceKey, sourceConn);
    }

    private static void writeToPeer(SelectionKey sourceKey, Connection sourceConn) throws IOException {
        SelectionKey destKey = sourceConn.peerKey;
        SocketChannel dest = (SocketChannel) destKey.channel();

        dest.write(sourceConn.buffer);

        if (sourceConn.buffer.hasRemaining()) {
            sourceKey.interestOps(sourceKey.interestOps() & ~SelectionKey.OP_READ);
            destKey.interestOps(destKey.interestOps() | SelectionKey.OP_WRITE);
        } else {
            sourceConn.buffer.clear();
            destKey.interestOps(destKey.interestOps() & ~SelectionKey.OP_WRITE);
            sourceKey.interestOps(sourceKey.interestOps() | SelectionKey.OP_READ);
        }
    }

    private static void close(SelectionKey key, Selector selector) {
        if (key != null) {
            try {
                key.channel().close();
                key.cancel();
            } catch (IOException ignored) {
            }
        }
        if (selector != null) {
            try {
                maintainConnectionPool(selector);
            } catch (IOException e) {
                System.err.println("Failed to maintain pool: " + e.getMessage());
            }
        }
    }
}
