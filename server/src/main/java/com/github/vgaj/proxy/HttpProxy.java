package com.github.vgaj.proxy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.*;

public class HttpProxy {

    private static int count = 0;

    private static class Connection {
        SocketChannel peer;
        ByteBuffer buffer = ByteBuffer.allocateDirect(8192);
    }

    private static class PendingConnection {
        SocketChannel client;
        ByteBuffer buffer;
        boolean isConnect;

        PendingConnection(SocketChannel client, ByteBuffer buffer, boolean isConnect) {
            this.client = client;
            this.buffer = buffer;
            this.isConnect = isConnect;
        }
    }

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

                if (!key.isValid())
                    continue;

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
                        handleConnect(key, selector);
                    } catch (IOException e) {
                        System.out.println("ERROR: failed to connect: " + e.getMessage());
                        close(key.channel());
                    }
                } else if (key.isReadable()) {
                    try {
                        handleRead(key, selector);
                    } catch (Exception e) {
                        System.out.println("ERROR: failed to read: " + e.getMessage());
                        close(key.channel());
                    }
                }
            }
        }
    }

    private static void handleConnect(SelectionKey key, Selector selector) throws IOException {
        SocketChannel server = (SocketChannel) key.channel();
        PendingConnection pending = (PendingConnection) key.attachment();

        if (server.finishConnect()) {
            registerTunnels(selector, pending.client, server, pending.buffer, pending.isConnect);
        }
    }

    private static void registerTunnels(Selector selector, SocketChannel channel, SocketChannel server, ByteBuffer buf,
            boolean isConnect) throws IOException {
        Connection c1 = new Connection();
        Connection c2 = new Connection();
        c1.peer = server;
        c2.peer = channel;

        channel.register(selector, SelectionKey.OP_READ, c1);
        server.register(selector, SelectionKey.OP_READ, c2);

        if (isConnect) {
            channel.write(ByteBuffer.wrap(
                    "HTTP/1.1 200 Connection Established\r\n\r\n".getBytes()));
        } else {
            server.write(ByteBuffer.wrap(buf.array(), 0, buf.limit()));
        }
    }

    private static void handleRead(SelectionKey key, Selector selector) throws IOException {
        SocketChannel channel = (SocketChannel) key.channel();
        Connection conn = (Connection) key.attachment();

        if (conn == null) {

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
                server.register(selector, SelectionKey.OP_CONNECT, new PendingConnection(channel, buf, isConnect));
                key.interestOps(0);
            } else {
                registerTunnels(selector, channel, server, buf, isConnect);
            }

            return;
        }

        // Normal data forwarding
        int read;
        try {
            read = channel.read(conn.buffer);
        } catch (IOException e) {
            System.out.println(e.toString());
            close(channel, conn.peer);
            return;
        }
        if (read == -1) {
            close(channel, conn.peer);
            return;
        }

        conn.buffer.flip();
        conn.peer.write(conn.buffer);
        conn.buffer.clear();
    }

    private static void close(SocketChannel a, SocketChannel b) {
        System.out.println("Connection closed, count is now " + --count);
        close(a);
        close(b);
    }

    private static void close(Channel chan) {
        try {
            chan.close();
        } catch (IOException ignored) {
        }
    }

}