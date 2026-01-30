package com.github.vgaj.proxy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;

public class ProxyServer {

    private static final int BROWSER_PORT = 8888;
    private static final int MOBILE_PORT = 9999;
    private static final String DEFAULT_AUTH_CODE = "5678";
    private static final String PENDING_AUTH = "PENDING_AUTH";

    // App IP -> queue of idle authenticated mobile connections from that app
    private static final Map<String, Queue<SelectionKey>> idleMobilesByApp = new HashMap<>();

    // Browser IP -> app IP it was last paired with
    private static final Map<String, String> browserToAppAffinity = new HashMap<>();
    private static String expectedAuthCode;

    private record BridgeConnection(SelectionKey peerKey, ByteBuffer buffer) {
    }

    public static void main(String[] args) throws IOException {
        expectedAuthCode = System.getenv().getOrDefault("PROXY_AUTH_CODE", DEFAULT_AUTH_CODE);
        System.out.println("Using auth code: " + expectedAuthCode);

        Selector selector = Selector.open();

        // Register shutdown hook to close all connections cleanly
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Shutting down - closing all connections...");
            for (SelectionKey key : selector.keys()) {
                try {
                    key.channel().close();
                } catch (IOException ignored) {
                }
            }
            try {
                selector.close();
            } catch (IOException ignored) {
            }
            System.out.println("Shutdown complete.");
        }));

        // Browser Listener
        ServerSocketChannel browserServer = ServerSocketChannel.open();
        browserServer.configureBlocking(false);
        browserServer.bind(new InetSocketAddress(BROWSER_PORT));
        browserServer.register(selector, SelectionKey.OP_ACCEPT, "BROWSER_ACCEPT");

        // Mobile Listener
        ServerSocketChannel mobileServer = ServerSocketChannel.open();
        mobileServer.configureBlocking(false);
        mobileServer.bind(new InetSocketAddress(MOBILE_PORT));
        mobileServer.register(selector, SelectionKey.OP_ACCEPT, "MOBILE_ACCEPT");

        System.out.println("ProxyServer started.");
        System.out.println("Listening for Browsers on port " + BROWSER_PORT);
        System.out.println("Listening for Mobiles on port " + MOBILE_PORT);

        while (true) {
            selector.select();
            Iterator<SelectionKey> keys = selector.selectedKeys().iterator();

            while (keys.hasNext()) {
                SelectionKey key = keys.next();
                keys.remove();

                if (!key.isValid())
                    continue;

                try {
                    if (key.isAcceptable()) {
                        handleAccept(key, selector);
                    } else if (key.isReadable()) {
                        handleRead(key);
                    } else if (key.isWritable()) {
                        handleWrite(key);
                    }
                } catch (Exception e) {
                    System.err.println("Error handling key: " + e.getMessage());
                    close(key);
                }
            }
        }
    }

    private static void handleAccept(SelectionKey key, Selector selector) throws IOException {
        ServerSocketChannel server = (ServerSocketChannel) key.channel();
        SocketChannel client = server.accept();
        client.configureBlocking(false);

        String type = (String) key.attachment();

        if ("MOBILE_ACCEPT".equals(type)) {
            System.out.println("New Mobile connected: " + client.getRemoteAddress() + " - awaiting auth");
            // Register for reading with PENDING_AUTH attachment to read auth code
            client.register(selector, SelectionKey.OP_READ, PENDING_AUTH);
        } else if ("BROWSER_ACCEPT".equals(type)) {
            System.out.println("New Browser connected: " + client.getRemoteAddress());

            String browserIp = getIp(client);
            String preferredApp = browserToAppAffinity.get(browserIp);

            SelectionKey mobileKey = null;
            String selectedAppIp = null;

            // Try preferred app first (affinity)
            if (preferredApp != null) {
                mobileKey = pollValidKey(preferredApp);
                if (mobileKey != null) {
                    selectedAppIp = preferredApp;
                }
            }

            // Fall back to any available app
            if (mobileKey == null) {
                Iterator<Map.Entry<String, Queue<SelectionKey>>> it = idleMobilesByApp.entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry<String, Queue<SelectionKey>> entry = it.next();
                    mobileKey = pollValidKey(entry.getKey());
                    if (mobileKey != null) {
                        selectedAppIp = entry.getKey();
                        break;
                    }
                }
            }

            if (mobileKey == null) {
                System.out.println("No mobile proxies available! Closing browser connection.");
                client.close();
                return;
            }

            browserToAppAffinity.put(browserIp, selectedAppIp);

            System.out.println("Pairing Browser " + client.getRemoteAddress() + " with Mobile "
                    + ((SocketChannel) mobileKey.channel()).getRemoteAddress()
                    + " (app " + selectedAppIp + ", affinity=" + (selectedAppIp.equals(preferredApp)) + ")");

            SelectionKey browserKey = client.register(selector, SelectionKey.OP_READ);

            pairUpChannels(browserKey, mobileKey);
        }
    }

    private static void pairUpChannels(SelectionKey browserKey, SelectionKey mobileKey) {
        BridgeConnection c1 = new BridgeConnection(mobileKey, ByteBuffer.allocateDirect(8192));
        BridgeConnection c2 = new BridgeConnection(browserKey, ByteBuffer.allocateDirect(8192));

        browserKey.attach(c1);
        mobileKey.attach(c2);

        browserKey.interestOps(SelectionKey.OP_READ);
        mobileKey.interestOps(SelectionKey.OP_READ);
    }

    private static void handleRead(SelectionKey key) throws IOException {
        Object attachment = key.attachment();
        SocketChannel channel = (SocketChannel) key.channel();

        // Handle pending authentication for mobile connections
        if (PENDING_AUTH.equals(attachment)) {
            ByteBuffer authBuffer = ByteBuffer.allocate(expectedAuthCode.length());
            int read = channel.read(authBuffer);
            if (read == -1) {
                System.out.println("Mobile disconnected before auth: " + channel.getRemoteAddress());
                close(key);
                return;
            }
            authBuffer.flip();
            byte[] authBytes = new byte[expectedAuthCode.length()];
            authBuffer.get(authBytes);
            String receivedCode = new String(authBytes, java.nio.charset.StandardCharsets.US_ASCII);

            if (expectedAuthCode.equals(receivedCode)) {
                String appIp = getIp(channel);
                System.out.println("Mobile authenticated successfully: " + channel.getRemoteAddress() + " (app " + appIp + ")");
                key.attach(null); // Clear the PENDING_AUTH marker
                key.interestOps(0); // Not interested in ops until paired
                idleMobilesByApp.computeIfAbsent(appIp, k -> new LinkedList<>()).add(key);
            } else {
                System.out.println("Mobile auth failed (got '" + receivedCode + "'): " + channel.getRemoteAddress());
                close(key);
            }
            return;
        }

        BridgeConnection conn = (BridgeConnection) attachment;

        if (conn == null) {
            // Should not happen for paired connections
            return;
        }

        int read = -1;
        try {
            read = channel.read(conn.buffer);
        } catch (IOException e) {
            read = -1;
        }

        if (read == -1) {
            close(key);
            close(conn.peerKey);
            return;
        }

        conn.buffer.flip();
        writeToPeer(key, conn);
    }

    private static void handleWrite(SelectionKey key) throws IOException {
        BridgeConnection conn = (BridgeConnection) key.attachment();
        SelectionKey sourceKey = conn.peerKey;
        BridgeConnection sourceConn = (BridgeConnection) sourceKey.attachment();
        writeToPeer(sourceKey, sourceConn);
    }

    private static void writeToPeer(SelectionKey sourceKey, BridgeConnection sourceConn) throws IOException {
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

    private static String getIp(SocketChannel channel) throws IOException {
        InetSocketAddress addr = (InetSocketAddress) channel.getRemoteAddress();
        return addr.getAddress().getHostAddress();
    }

    private static SelectionKey pollValidKey(String appIp) {
        Queue<SelectionKey> queue = idleMobilesByApp.get(appIp);
        if (queue == null) {
            return null;
        }
        while (!queue.isEmpty()) {
            SelectionKey key = queue.poll();
            if (key.isValid()) {
                if (queue.isEmpty()) {
                    idleMobilesByApp.remove(appIp);
                }
                return key;
            }
        }
        idleMobilesByApp.remove(appIp);
        return null;
    }

    private static void close(SelectionKey key) {
        if (key != null) {
            try {
                key.channel().close();
                key.cancel();
            } catch (IOException ignored) {
            }
        }
    }
}
