package com.github.vgaj.proxy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import android.util.Log;

public class MobileProxy implements Runnable {

    private static final String TAG = "MobileProxy";

    public interface ConnectionListener {
        void onRequestReceived(String request);
        void onConnectionSuccess(String host, int port);
        void onConnectionFailure(String host, int port, String request, String error);
        void onServerConnectionFailed(String error, int retryMinutes);
        void onServerConnectionAttempt(String serverHost, int serverPort);
        void onMaxConnectionsReached(int max);
    }

    private static final int NONCE_SIZE = 32;
    private static final int HMAC_SIZE = 32;
    private static final String HMAC_ALGO = "HmacSHA256";
    private static final int RETRY_INTERVAL_MINUTES = 1;

    private final String serverHost;
    private final int serverPort;
    private final String authCode;
    private final byte[] authKeyBytes;
    private volatile boolean running = true;
    private volatile boolean retryScheduled = false;
    private boolean maxConnectionsReported = false;
    private Selector selector;
    private ConnectionListener statusReporter;

    public MobileProxy(String serverHost, int serverPort, String authCode) {
        this.serverHost = serverHost;
        this.serverPort = serverPort;
        this.authCode = authCode;
        this.authKeyBytes = authCode.getBytes(StandardCharsets.UTF_8);
    }

    public void setConnectionListener(ConnectionListener listener) {
        this.statusReporter = listener;
    }

    private record Connection(SelectionKey peerKey, ByteBuffer buffer) {
    }

    private static final class PendingChallengeResponse {
        final ByteBuffer nonceBuffer = ByteBuffer.allocate(NONCE_SIZE);
        ByteBuffer responseBuffer;  // set after nonce fully received
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

    private static final int MAX_IDLE = 20;
    private static final int MAX_TOTAL = 500;
    private final Set<SocketChannel> tunnels = new HashSet<>();

    public void stop() {
        running = false;
        if (selector != null) {
            selector.wakeup();
        }
    }

    private void closeAllConnections() {
        // Close all tunnels
        for (SocketChannel tunnel : tunnels) {
            try {
                tunnel.close();
            } catch (IOException ignored) {
            }
        }
        tunnels.clear();

        // Close all registered keys (includes target connections)
        if (selector != null) {
            for (SelectionKey key : selector.keys()) {
                try {
                    key.channel().close();
                } catch (IOException ignored) {
                }
                key.cancel();
            }
        }
    }

    @Override
    public void run() {
        try {
            selector = Selector.open();
            Log.d(TAG, "MobileProxy started. Targeting " + serverHost + ":" + serverPort);

            // Initial pool population
            if (statusReporter != null) {
                statusReporter.onServerConnectionAttempt(serverHost, serverPort);
            }
            maintainConnectionPool(selector);

            while (running) {
                selector.select();
                if (!running)
                    break;

                // Maintain pool after select wakes up (handles retry after backoff)
                maintainConnectionPool(selector);

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
                        Log.e(TAG, "Error in main loop", e);
                        close(key, selector);
                    }
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "IOException in run()", e);
        } finally {
            closeAllConnections();
            if (selector != null) {
                try {
                    selector.close();
                } catch (IOException e) {
                    // ignore
                }
            }
        }
    }

    private void maintainConnectionPool(Selector selector) throws IOException {
        if (retryScheduled) {
            return; // Don't create new connections while waiting to retry
        }
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
                // target) or is still completing the challenge-response handshake
                if (key.attachment() == null || key.attachment() instanceof PendingChallengeResponse) {
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

        if (needed > 0 && canCreate <= 0) {
            if (!maxConnectionsReported) {
                maxConnectionsReported = true;
                if (statusReporter != null) {
                    statusReporter.onMaxConnectionsReached(MAX_TOTAL);
                }
            }
        } else {
            maxConnectionsReported = false;
        }

        for (int i = 0; i < toCreate; i++) {
            initiateConnection(selector);
        }

        if (toCreate > 0) {
            Log.d(TAG, "Pool maintenance: Created " + toCreate + " new connections. Total: " + tunnels.size()
                    + ", Idle/Pending: " + (idleOrPending + toCreate));
        }
    }

    private void initiateConnection(Selector selector) {
        try {
            SocketChannel tunnel = SocketChannel.open();
            tunnel.configureBlocking(false);
            tunnel.connect(new InetSocketAddress(serverHost, serverPort));
            tunnel.register(selector, SelectionKey.OP_CONNECT);
            tunnels.add(tunnel);
        } catch (IOException e) {
            notifyServerConnectionFailed("Failed to initiate connection: " + e.getMessage());
        }
    }

    private void notifyServerConnectionFailed(String error) {
        Log.e(TAG, error + " - retrying in " + RETRY_INTERVAL_MINUTES
                + (RETRY_INTERVAL_MINUTES == 1 ? " minute" : " minutes"));
        if (!retryScheduled) {
            if (statusReporter != null) {
                statusReporter.onServerConnectionFailed(error, RETRY_INTERVAL_MINUTES);
            }
            scheduleRetry();
        }
    }

    private void scheduleRetry() {
        retryScheduled = true;
        new Thread(() -> {
            try {
                Thread.sleep(RETRY_INTERVAL_MINUTES * 60000);
                if (!running) {
                    retryScheduled = false;
                    return;
                }
                if (statusReporter != null) {
                    statusReporter.onServerConnectionAttempt(serverHost, serverPort);
                }
                retryScheduled = false;
                if (selector != null) {
                    selector.wakeup();
                }
            } catch (InterruptedException ignored) {
                retryScheduled = false;
            }
        }).start();
    }

    private void handleConnect(SelectionKey key, Selector selector) throws IOException {
        SocketChannel channel = (SocketChannel) key.channel();
        try {
            if (channel.finishConnect()) {
                retryScheduled = false; // Connection succeeded, clear backoff
                Log.d(TAG, "Connected to ProxyServer. Awaiting challenge...");
                PendingChallengeResponse pending = new PendingChallengeResponse();
                key.attach(pending);
                key.interestOps(SelectionKey.OP_READ);
            }
        } catch (IOException e) {
            tunnels.remove(channel);
            key.cancel();
            try {
                channel.close();
            } catch (IOException ignored) {
            }
            notifyServerConnectionFailed("Failed to connect to server: " + e.getMessage());
        }
    }

    private void handleRead(SelectionKey key, Selector selector) throws IOException {
        SocketChannel channel = (SocketChannel) key.channel();
        Object attachment = key.attachment();

        // Handle challenge-response authentication
        if (attachment instanceof PendingChallengeResponse pending) {
            int read = channel.read(pending.nonceBuffer);
            if (read == -1) {
                Log.e(TAG, "ProxyServer disconnected during auth handshake");
                close(key, selector);
                return;
            }
            if (pending.nonceBuffer.hasRemaining()) {
                return; // Partial read - wait for more nonce bytes
            }
            // Full nonce received - compute HMAC and send response
            pending.nonceBuffer.flip();
            byte[] nonce = new byte[NONCE_SIZE];
            pending.nonceBuffer.get(nonce);
            byte[] hmac = computeHmac(authKeyBytes, nonce);
            pending.responseBuffer = ByteBuffer.wrap(hmac);
            // Try to write the HMAC response immediately
            channel.write(pending.responseBuffer);
            if (pending.responseBuffer.hasRemaining()) {
                // Partial write - register for OP_WRITE to finish
                key.interestOps(SelectionKey.OP_WRITE);
            } else {
                // Full HMAC sent - auth complete, transition to idle
                Log.d(TAG, "Auth challenge-response completed. Waiting for traffic...");
                key.attach(null);
                key.interestOps(SelectionKey.OP_READ);
            }
            return;
        }

        // If no attachment, this is the Tunnel reading from Server (Browser request)
        if (attachment == null) {
            ByteBuffer buf = ByteBuffer.allocate(8192);
            int n = channel.read(buf);
            if (n == -1) {
                Log.d(TAG, "ProxyServer closed connection");
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

    private void processInitialRequest(SelectionKey tunnelKey, Selector selector, ByteBuffer buffer)
            throws IOException {
        String request = new String(buffer.array(), 0, buffer.limit(), StandardCharsets.ISO_8859_1);

        if (statusReporter != null) {
            statusReporter.onRequestReceived(request);
        }

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
            Log.e(TAG, "Could not parse host from request");
            if (statusReporter != null) {
                statusReporter.onConnectionFailure("unknown", 0, request, "Could not parse host from request");
            }
            tunnelKey.channel().close();
            return;
        }

        Log.d(TAG, "Connecting to target: " + host + ":" + port);

        SocketChannel target = SocketChannel.open();
        try {
            target.configureBlocking(true);
            target.connect(new InetSocketAddress(host, port));
            target.configureBlocking(false);
        } catch (IOException e) {
            Log.e(TAG, "Failed to connect to " + host + ":" + port, e);
            if (statusReporter != null) {
                statusReporter.onConnectionFailure(host, port, request, e.getMessage());
            }
            tunnelKey.channel().close();
            return;
        }

        if (statusReporter != null) {
            statusReporter.onConnectionSuccess(host, port);
        }

        registerTarget(selector, tunnelKey, target, buffer, headerEnd, isConnect);
    }

    private void registerTarget(Selector selector, SelectionKey tunnelKey, SocketChannel target,
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

    private void handleWrite(SelectionKey key) throws IOException {
        Object attachment = key.attachment();

        // Handle partial HMAC response write during auth handshake
        if (attachment instanceof PendingChallengeResponse pending) {
            SocketChannel channel = (SocketChannel) key.channel();
            channel.write(pending.responseBuffer);
            if (!pending.responseBuffer.hasRemaining()) {
                // Full HMAC sent - auth complete, transition to idle
                Log.d(TAG, "Auth challenge-response completed. Waiting for traffic...");
                key.attach(null);
                key.interestOps(SelectionKey.OP_READ);
            }
            return;
        }

        Connection conn = (Connection) attachment;
        SelectionKey sourceKey = conn.peerKey;
        Connection sourceConn = (Connection) sourceKey.attachment();
        writeToPeer(sourceKey, sourceConn);
    }

    private void writeToPeer(SelectionKey sourceKey, Connection sourceConn) throws IOException {
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

    private void close(SelectionKey key, Selector selector) {
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
                Log.e(TAG, "Failed to maintain pool", e);
            }
        }
    }
}
