package com.github.vgaj.proxy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;

public class HttpProxy {

    private static int count = 0;

    private record Connection(SelectionKey peerKey, ByteBuffer buffer) {}

    private record PendingConnection(SelectionKey clientKey, ByteBuffer buffer, boolean isConnect) {}

    public static void main(String[] args) throws Exception {
        int port = 8888;

        Selector selector = Selector.open();

        ServerSocketChannel server = ServerSocketChannel.open();
        server.configureBlocking(false);
        server.bind(new InetSocketAddress(port));
        server.register(selector, SelectionKey.OP_ACCEPT);

        System.out.println("proxy listening on port " + port);

        while (true) {
            selector.select();
            Iterator<SelectionKey> keys = selector.selectedKeys().iterator();

            while (keys.hasNext()) {
                SelectionKey key = keys.next();
                keys.remove();

                if (!key.isValid()) continue;

                if (key.isAcceptable()) {
                    try {
                        SocketChannel client = server.accept();
                        client.configureBlocking(false);
                        client.register(selector, SelectionKey.OP_READ);
                    } catch (IOException e) {
                        System.out.println("ERROR: failed to accept connection: " + e.getMessage());
                    }
                } else if (key.isConnectable()) {
                    try {
                        SocketChannel channel = (SocketChannel) key.channel();
                        PendingConnection pending = (PendingConnection) key.attachment();

                        if (channel.finishConnect()) {
                            pairUpChannels(pending.clientKey(), key, pending.buffer(), pending.isConnect());
                        }
                    } catch (IOException e) {
                        System.out.println("ERROR: failed to connect: " + e.getMessage());
                        close(key);
                    }
                } else if (key.isReadable()) {
                    try {
                        handleRead(key, selector);
                    } catch (Exception e) {
                        System.out.println("ERROR: failed to read: " + e.getMessage());
                        close(key);
                    }
                } else if (key.isWritable()) {
                    try {
                        handleWrite(key);
                    } catch (Exception e) {
                        System.out.println("ERROR: failed to write: " + e.getMessage());
                        close(key);
                    }
                }
            }
        }
    }

    private static void pairUpChannels(SelectionKey clientKey, SelectionKey serverKey, ByteBuffer buf, boolean isConnect) throws IOException {
        Connection c1 = new Connection(serverKey, ByteBuffer.allocateDirect(8192));
        Connection c2 = new Connection(clientKey, ByteBuffer.allocateDirect(8192));

        SocketChannel client = (SocketChannel) clientKey.channel();
        SocketChannel server = (SocketChannel) serverKey.channel();

        clientKey.attach(c1);
        serverKey.attach(c2);

        clientKey.interestOps(SelectionKey.OP_READ);
        serverKey.interestOps(SelectionKey.OP_READ);

        if (isConnect) {
            client.write(ByteBuffer.wrap("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes()));
        } else {
            server.write(ByteBuffer.wrap(buf.array(), 0, buf.limit()));
        }
    }

    private static void handleRead(SelectionKey key, Selector selector) throws IOException {
        SocketChannel channel = (SocketChannel) key.channel();
        Object attachment = key.attachment();

        if (attachment == null) {
            // First read: parse request and connect upstream
            ByteBuffer buf = ByteBuffer.allocate(4096);
            int n = channel.read(buf);
            if (n == -1) {
                channel.close();
                return;
            }

            buf.flip();
            String request = new String(buf.array(), 0, buf.limit());

            String host;
            int port;

            // false means HTTP not HTTPS
            boolean isConnect = request.startsWith("CONNECT");

            if (isConnect) {
                String[] parts = request.split(" ");
                String[] hp = parts[1].split(":");
                host = hp[0];
                port = Integer.parseInt(hp[1]);
            } else {
                host = null;
                port = 80;
                for (String line : request.split("\r\n")) {
                    if (line.toLowerCase().startsWith("host:")) {
                        host = line.substring(5).trim();
                        if (host.contains(":")) {
                            String[] hp = host.split(":");
                            host = hp[0];
                            port = Integer.parseInt(hp[1]);
                        }
                        break;
                    }
                }
            }

            if (host == null) {
                System.out.println("ERROR: Unable to identify host from: " + request);
                channel.close();
                return;
            } else {
                System.out.println("New connection to " + host + ", count is now " + ++count);
            }

            SocketChannel server = SocketChannel.open();
            server.configureBlocking(false);

            if (!server.connect(new InetSocketAddress(host, port))) {
                SelectionKey serverKey = server.register(selector, SelectionKey.OP_CONNECT);
                serverKey.attach(new PendingConnection(key, buf, isConnect));
                key.interestOps(0);
            } else {
                SelectionKey serverKey = server.register(selector, 0);
                pairUpChannels(key, serverKey, buf, isConnect);
            }
            return;
        }

        Connection conn = (Connection) attachment;

        // Normal data forwarding
        int read;
        try {
            read = channel.read(conn.buffer());
        } catch (IOException e) {
            read = -1;
        }
        if (read == -1) {
            close(key);
            close(conn.peerKey());
            System.out.println("Connection closed, count is now " + --count);
            return;
        }

        conn.buffer().flip();
        writeToPeer(key, conn);
    }

    private static void handleWrite(SelectionKey key) throws IOException {
        Connection conn = (Connection) key.attachment();
        SelectionKey sourceKey = conn.peerKey();
        Connection sourceConn = (Connection) sourceKey.attachment();
        writeToPeer(sourceKey, sourceConn);
    }

    private static void writeToPeer(SelectionKey sourceKey, Connection sourceConn) throws IOException {
        SelectionKey destKey = sourceConn.peerKey();
        SocketChannel dest = (SocketChannel) destKey.channel();

        dest.write(sourceConn.buffer());

        if (sourceConn.buffer().hasRemaining()) {
            // Buffer full, enable write on dest, disable read on source
            sourceKey.interestOps(sourceKey.interestOps() & ~SelectionKey.OP_READ);
            destKey.interestOps(destKey.interestOps() | SelectionKey.OP_WRITE);
        } else {
            // Buffer drained, disable write on dest, enable read on source
            sourceConn.buffer().clear();
            destKey.interestOps(destKey.interestOps() & ~SelectionKey.OP_WRITE);
            sourceKey.interestOps(sourceKey.interestOps() | SelectionKey.OP_READ);
        }
    }

    private static void close(SelectionKey key) {
        if (key != null) {
            try {
                key.channel().close();
            } catch (IOException ignored) {
            }
            key.cancel();
        }
    }

}