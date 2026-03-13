package com.github.vgaj.proxy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Queue;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public class ProxyServer {

    private static final int DEFAULT_BROWSER_PORT = 8888;
    private static final int DEFAULT_MOBILE_PORT = 9999;
    private static final String DEFAULT_AUTH_CODE = "5678";

    private static final int NONCE_SIZE = 32;
    private static final int HMAC_SIZE = 32;
    private static final String HMAC_ALGO = "HmacSHA256";
    private static final SecureRandom secureRandom = new SecureRandom();
    private static byte[] authKeyBytes;

    // Queue of idle mobile connections waiting for work
    private static final Queue<SelectionKey> idleMobiles = new LinkedList<>();

    private record BridgeConnection(SelectionKey peerKey, ByteBuffer buffer) {
    }

    private enum AuthState { SENDING_CHALLENGE, AWAITING_RESPONSE }

    private static final class PendingAuth {
        AuthState state;
        final byte[] nonce;
        final ByteBuffer writeBuffer;   // for sending the nonce
        final ByteBuffer readBuffer;    // for reading the 32-byte HMAC response

        PendingAuth(byte[] nonce) {
            this.nonce = nonce;
            this.state = AuthState.SENDING_CHALLENGE;
            this.writeBuffer = ByteBuffer.wrap(nonce.clone());
            this.readBuffer = ByteBuffer.allocate(HMAC_SIZE);
        }
    }

    private static byte[] computeHmac(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(key, HMAC_ALGO));
            return mac.doFinal(data);
        } catch (Exception e) {
            throw new RuntimeException("HMAC computation failed", e);
        }
    }

    public static void main(String[] args) throws IOException {
        String expectedAuthCode = System.getenv().getOrDefault("AUTH_CODE", DEFAULT_AUTH_CODE);
        authKeyBytes = expectedAuthCode.getBytes(StandardCharsets.UTF_8);
        System.out.println("Using auth code: " + expectedAuthCode);

        int browserPort = Integer.parseInt(System.getenv().getOrDefault("PROXY_BROWSER_PORT", String.valueOf(DEFAULT_BROWSER_PORT)));
        int mobilePort = Integer.parseInt(System.getenv().getOrDefault("PROXY_MOBILE_PORT", String.valueOf(DEFAULT_MOBILE_PORT)));

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
        browserServer.bind(new InetSocketAddress(browserPort));
        browserServer.register(selector, SelectionKey.OP_ACCEPT, "BROWSER_ACCEPT");

        // Mobile Listener
        ServerSocketChannel mobileServer = ServerSocketChannel.open();
        mobileServer.configureBlocking(false);
        mobileServer.bind(new InetSocketAddress(mobilePort));
        mobileServer.register(selector, SelectionKey.OP_ACCEPT, "MOBILE_ACCEPT");

        System.out.println("ProxyServer started.");
        System.out.println("Listening for Browsers on port " + browserPort);
        System.out.println("Listening for Mobiles on port " + mobilePort);

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
            System.out.println("New Mobile connected: " + client.getRemoteAddress() + " - sending challenge");
            byte[] nonce = new byte[NONCE_SIZE];
            secureRandom.nextBytes(nonce);
            PendingAuth pendingAuth = new PendingAuth(nonce);
            // Try to write the nonce immediately
            int written = client.write(pendingAuth.writeBuffer);
            if (pendingAuth.writeBuffer.hasRemaining()) {
                // Partial write - register for OP_WRITE to finish sending
                client.register(selector, SelectionKey.OP_WRITE, pendingAuth);
            } else {
                // Full nonce sent - transition to awaiting response
                pendingAuth.state = AuthState.AWAITING_RESPONSE;
                client.register(selector, SelectionKey.OP_READ, pendingAuth);
            }
        } else if ("BROWSER_ACCEPT".equals(type)) {
            System.out.println("New Browser connected: " + client.getRemoteAddress());

            SelectionKey mobileKey = idleMobiles.poll();
            while (mobileKey != null && !mobileKey.isValid()) {
                mobileKey = idleMobiles.poll(); // Discard invalid keys
            }

            if (mobileKey == null) {
                System.out.println("No mobile proxies available! Closing browser connection.");
                client.close();
                return;
            }

            System.out.println("Pairing Browser " + client.getRemoteAddress() + " with Mobile "
                    + ((SocketChannel) mobileKey.channel()).getRemoteAddress());

            SelectionKey browserKey = client.register(selector, SelectionKey.OP_READ);

            BridgeConnection mobileBridge = new BridgeConnection(mobileKey, ByteBuffer.allocateDirect(8192));
            BridgeConnection browserBridge = new BridgeConnection(browserKey, ByteBuffer.allocateDirect(8192));

            browserKey.attach(mobileBridge);
            mobileKey.attach(browserBridge);

            browserKey.interestOps(SelectionKey.OP_READ);
            mobileKey.interestOps(SelectionKey.OP_READ);
        }
    }

    private static void handleRead(SelectionKey key) throws IOException {
        Object attachment = key.attachment();
        SocketChannel channel = (SocketChannel) key.channel();

        // Handle pending authentication for mobile connections
        if (attachment instanceof PendingAuth pendingAuth) {
            int read = channel.read(pendingAuth.readBuffer);
            if (read == -1) {
                System.out.println("Mobile disconnected during auth: " + channel.getRemoteAddress());
                close(key);
                return;
            }
            if (pendingAuth.readBuffer.hasRemaining()) {
                return; // Partial read - wait for more data
            }
            // All 32 bytes received - verify HMAC
            pendingAuth.readBuffer.flip();
            byte[] receivedHmac = new byte[HMAC_SIZE];
            pendingAuth.readBuffer.get(receivedHmac);
            byte[] expectedHmac = computeHmac(authKeyBytes, pendingAuth.nonce);

            if (MessageDigest.isEqual(expectedHmac, receivedHmac)) {
                System.out.println("Mobile authenticated successfully: " + channel.getRemoteAddress());
                key.attach(null);
                key.interestOps(0); // Not interested in ops until paired
                idleMobiles.add(key);
            } else {
                System.out.println("HMAC mismatch - Mobile auth failed: " + channel.getRemoteAddress());
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
        } catch (IOException ignored) {
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
        Object attachment = key.attachment();

        // Handle partial nonce write during auth handshake
        if (attachment instanceof PendingAuth pendingAuth) {
            SocketChannel channel = (SocketChannel) key.channel();
            channel.write(pendingAuth.writeBuffer);
            if (!pendingAuth.writeBuffer.hasRemaining()) {
                // Nonce fully sent - transition to awaiting HMAC response
                pendingAuth.state = AuthState.AWAITING_RESPONSE;
                key.interestOps(SelectionKey.OP_READ);
            }
            return;
        }

        BridgeConnection conn = (BridgeConnection) attachment;
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
