package com.pinfileshare;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Random;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int PICK_FILE = 41;
    private static final int SAVE_FILE = 42;
    private static final int PORT = 8765;
    private final ExecutorService io = Executors.newCachedThreadPool();
    private Uri selectedUri;
    private String selectedName = "shared-file";
    private long selectedSize = -1;
    private String pin;
    private long expiresAt;
    private int failedPins;
    private boolean transferUsed;
    private volatile boolean serverRunning;
    private ServerSocket serverSocket;
    private String receiveIp;
    private String receivePin;
    private String expectedName;
    private long expectedSize;

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(247, 249, 252));
        getWindow().setNavigationBarColor(Color.rgb(247, 249, 252));
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        showHome();
    }

    private TextView text(String value, float size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(24), dp(22), dp(24), dp(24));
        layout.setBackgroundColor(Color.rgb(247, 249, 252));
        return layout;
    }

    private void addGap(LinearLayout layout, int height) {
        View gap = new View(this);
        layout.addView(gap, new LinearLayout.LayoutParams(1, dp(height)));
    }

    private Button button(String label, int background, int foreground) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(16);
        button.setTextColor(foreground);
        button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(background));
        button.setMinHeight(dp(56));
        return button;
    }

    private void showHome() {
        LinearLayout root = column();
        TextView brand = text("PIN FILE SHARE", 12, Color.rgb(74, 105, 145));
        brand.setLetterSpacing(0.12f);
        root.addView(brand);
        addGap(root, 28);
        TextView title = text("Send files.\nSimply.", 34, Color.rgb(22, 34, 53));
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title);
        addGap(root, 10);
        root.addView(text("Transfer a file between phones on the same Wi-Fi network.", 16,
            Color.rgb(92, 105, 123)));
        addGap(root, 30);

        LinearLayout sendCard = card();
        sendCard.addView(text("SEND A FILE", 12, Color.rgb(74, 105, 145)));
        addGap(sendCard, 8);
        sendCard.addView(text("Choose a file to share", 20, Color.rgb(22, 34, 53)));
        addGap(sendCard, 6);
        sendCard.addView(text("Creates a temporary PIN for one transfer.", 14,
            Color.rgb(92, 105, 123)));
        addGap(sendCard, 18);
        Button send = button("Choose file", Color.rgb(36, 99, 235), Color.WHITE);
        send.setOnClickListener(v -> chooseFile());
        sendCard.addView(send);
        root.addView(sendCard);

        addGap(root, 16);
        LinearLayout receiveCard = card();
        receiveCard.addView(text("RECEIVE A FILE", 12, Color.rgb(74, 105, 145)));
        addGap(receiveCard, 8);
        receiveCard.addView(text("Connect to sender", 20, Color.rgb(22, 34, 53)));
        addGap(receiveCard, 6);
        receiveCard.addView(text("Enter the sender's local IP address and PIN.", 14,
            Color.rgb(92, 105, 123)));
        addGap(receiveCard, 18);
        Button receive = button("Enter sender details", Color.rgb(226, 234, 246), Color.rgb(22, 52, 92));
        receive.setOnClickListener(v -> showReceiveForm());
        receiveCard.addView(receive);
        root.addView(receiveCard);

        addGap(root, 22);
        TextView note = text("LOCAL WI-FI MVP • PIN EXPIRES IN 10 MINUTES", 11,
            Color.rgb(130, 140, 153));
        note.setGravity(Gravity.CENTER);
        root.addView(note);
        setContentView(root);
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(18), dp(18), dp(18));
        card.setBackgroundColor(Color.WHITE);
        return card;
    }

    private void chooseFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, PICK_FILE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == IntentIntegrator.REQUEST_CODE) {
            IntentResult scan = IntentIntegrator.parseActivityResult(requestCode, resultCode, data);
            if (scan != null && scan.getContents() != null) {
                connectFromQr(scan.getContents());
            }
            return;
        }
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == PICK_FILE) {
            selectedUri = uri;
            try {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) { }
            selectedName = queryName(uri);
            selectedSize = querySize(uri);
            startSharing();
        } else if (requestCode == SAVE_FILE) {
            Uri destination = uri;
            io.execute(() -> downloadTo(destination));
        }
    }

    private void startQrScan() {
        new IntentIntegrator(this)
            .setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
            .setPrompt("Scan the sender's PIN File Share QR")
            .setBeepEnabled(false)
            .setOrientationLocked(false)
            .initiateScan();
    }

    private void connectFromQr(String content) {
        try {
            Uri parsed = Uri.parse(content);
            String scheme = parsed.getScheme();
            String host = parsed.getHost();
            String ip = parsed.getQueryParameter("ip");
            String scannedPin = parsed.getQueryParameter("pin");
            if (scheme == null || !scheme.equalsIgnoreCase("pfs")
                    || host == null || !host.equalsIgnoreCase("connect")
                    || ip == null || !ip.matches("\\d{1,3}(\\.\\d{1,3}){3}")
                    || scannedPin == null || !scannedPin.matches("\\d{6}")) {
                throw new IllegalArgumentException("This is not a valid PIN File Share QR code.");
            }
            receiveIp = ip;
            receivePin = scannedPin;
            Toast.makeText(this, "QR scanned. Checking sender…", Toast.LENGTH_SHORT).show();
            io.execute(this::checkSender);
        } catch (Exception e) {
            Toast.makeText(this, "Invalid QR code: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private Bitmap makeQrBitmap(String content) throws Exception {
        BitMatrix matrix = new MultiFormatWriter().encode(content, BarcodeFormat.QR_CODE, 640, 640);
        Bitmap bitmap = Bitmap.createBitmap(matrix.getWidth(), matrix.getHeight(), Bitmap.Config.ARGB_8888);
        for (int y = 0; y < matrix.getHeight(); y++) {
            for (int x = 0; x < matrix.getWidth(); x++) {
                bitmap.setPixel(x, y, matrix.get(x, y) ? Color.BLACK : Color.WHITE);
            }
        }
        return bitmap;
    }

    private LinearLayout makeSharePreview(String ip) {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(20), dp(8), dp(20), dp(8));
        panel.addView(text("File: " + selectedName, 16, Color.rgb(22, 34, 53)));
        panel.addView(text("Size: " + readableSize(selectedSize), 14, Color.rgb(92, 105, 123)));
        panel.addView(text("Sender IP: " + ip + "   Port: " + PORT, 13, Color.rgb(92, 105, 123)));
        panel.addView(text("PIN: " + pin, 24, Color.rgb(22, 52, 92)));
        addGap(panel, 8);
        ImageView qr = new ImageView(this);
        qr.setScaleType(ImageView.ScaleType.FIT_CENTER);
        try {
            String payload = "PFS://connect?ip=" + ip + "&pin=" + pin;
            qr.setImageBitmap(makeQrBitmap(payload));
            panel.addView(qr, new LinearLayout.LayoutParams(dp(236), dp(236)));
            TextView note = text("On the receiving phone, tap “Scan sender QR”. Both phones must be on the same trusted Wi-Fi. QR contains the local IP and temporary PIN.", 13, Color.rgb(92, 105, 123));
            panel.addView(note);
        } catch (Exception e) {
            panel.addView(text("QR generation failed. Use the IP and PIN above.", 13, Color.RED));
        }
        addGap(panel, 6);
        panel.addView(text("PIN expires in 10 minutes and allows one download. Local MVP traffic is not encrypted.", 12, Color.rgb(130, 70, 45)));
        return panel;
    }

    private String queryName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0) return c.getString(i);
            }
        } catch (Exception ignored) { }
        return "shared-file";
    }

    private long querySize(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.SIZE);
                if (i >= 0 && !c.isNull(i)) return c.getLong(i);
            }
        } catch (Exception ignored) { }
        return -1;
    }

    private void startSharing() {
        stopServer();
        pin = String.format(Locale.US, "%06d", new Random().nextInt(1_000_000));
        expiresAt = System.currentTimeMillis() + 10 * 60 * 1000L;
        failedPins = 0;
        transferUsed = false;
        serverRunning = true;
        io.execute(this::serveLoop);
        String ip = localWifiIp();
        if (ip.startsWith("Unavailable")) {
            stopServer();
            Toast.makeText(this, "Connect to Wi-Fi before sharing.", Toast.LENGTH_LONG).show();
            return;
        }
        new AlertDialog.Builder(this)
            .setTitle("File ready to share")
            .setView(makeSharePreview(ip))
            .setPositiveButton("Keep sharing", (d, w) -> { })
            .setNegativeButton("Stop sharing", (d, w) -> stopServer())
            .setOnCancelListener(d -> stopServer())
            .show();
    }

    private String localWifiIp() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            int ip = wm.getConnectionInfo().getIpAddress();
            if (ip == 0) return "Unavailable — connect to Wi-Fi";
            return String.format(Locale.US, "%d.%d.%d.%d", ip & 255, (ip >> 8) & 255,
                (ip >> 16) & 255, (ip >> 24) & 255);
        } catch (Exception e) {
            return "Unavailable — connect to Wi-Fi";
        }
    }

    private String readableSize(long size) {
        if (size < 0) return "Unknown";
        if (size < 1024) return size + " B";
        if (size < 1024 * 1024) return String.format(Locale.US, "%.1f KB", size / 1024.0);
        return String.format(Locale.US, "%.1f MB", size / (1024.0 * 1024.0));
    }

    private void serveLoop() {
        try (ServerSocket ss = new ServerSocket(PORT)) {
            serverSocket = ss;
            ss.setSoTimeout(1000);
            while (serverRunning && System.currentTimeMillis() < expiresAt && !transferUsed) {
                try {
                    Socket socket = ss.accept();
                    socket.setSoTimeout(10000);
                    io.execute(() -> handleClient(socket));
                } catch (java.net.SocketTimeoutException ignored) { }
            }
        } catch (Exception e) {
            if (serverRunning) runOnUiThread(() -> Toast.makeText(this,
                "Could not start sharing server: " + e.getMessage(), Toast.LENGTH_LONG).show());
        } finally {
            serverRunning = false;
        }
    }

    private void handleClient(Socket socket) {
        try (Socket client = socket) {
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                new BufferedInputStream(client.getInputStream()), StandardCharsets.US_ASCII));
            String request = reader.readLine();
            if (request == null) return;
            String[] parts = request.split(" ");
            String path = parts.length > 1 ? parts[1] : "/";
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) { }
            String supplied = "";
            int q = path.indexOf("pin=");
            if (q >= 0) supplied = URLDecoder.decode(path.substring(q + 4).split("&")[0], "UTF-8");
            synchronized (this) {
                if (!serverRunning || System.currentTimeMillis() > expiresAt || failedPins >= 5 || transferUsed) {
                    writeText(client.getOutputStream(), 410, "Gone", "PIN expired or transfer unavailable");
                    return;
                }
                if (!pin.equals(supplied)) {
                    failedPins++;
                    writeText(client.getOutputStream(), 403, "Forbidden", "Invalid PIN");
                    return;
                }
                if (path.startsWith("/info")) {
                    String encodedName = android.util.Base64.encodeToString(selectedName.getBytes(StandardCharsets.UTF_8),
                        android.util.Base64.URL_SAFE | android.util.Base64.NO_WRAP | android.util.Base64.NO_PADDING);
                    byte[] body = ("name=" + encodedName + "\nsize=" + selectedSize + "\n").getBytes(StandardCharsets.UTF_8);
                    OutputStream out = client.getOutputStream();
                    out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: "
                        + body.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    out.write(body); out.flush();
                    return;
                }
                if (!path.startsWith("/download")) {
                    writeText(client.getOutputStream(), 404, "Not Found", "Not found");
                    return;
                }
                transferUsed = true;
            }
            OutputStream out = new BufferedOutputStream(client.getOutputStream());
            InputStream in = getContentResolver().openInputStream(selectedUri);
            if (in == null) {
                writeText(out, 500, "Server Error", "Cannot open selected file");
                return;
            }
            out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: "
                + (selectedSize >= 0 ? selectedSize : 0) + "\r\nConnection: close\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
            byte[] buffer = new byte[32 * 1024];
            int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            out.flush();
            in.close();
            runOnUiThread(() -> Toast.makeText(this, "Transfer sent.", Toast.LENGTH_LONG).show());
        } catch (Exception e) {
            runOnUiThread(() -> Toast.makeText(this, "Transfer failed: " + e.getMessage(),
                Toast.LENGTH_LONG).show());
        }
    }

    private void writeText(OutputStream out, int code, String reason, String body) throws Exception {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        out.write(("HTTP/1.1 " + code + " " + reason + "\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: "
            + bytes.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.write(bytes); out.flush();
    }

    private void showReceiveForm() {
        LinearLayout form = new LinearLayout(this);
        form.setPadding(dp(8), dp(8), dp(8), 0);
        form.setOrientation(LinearLayout.VERTICAL);
        EditText ip = new EditText(this);
        ip.setSingleLine(true);
        ip.setHint("Sender IP, e.g. 192.168.1.10");
        EditText pinInput = new EditText(this);
        pinInput.setSingleLine(true);
        pinInput.setHint("6-digit PIN");
        pinInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        form.addView(ip);
        form.addView(pinInput);
        new AlertDialog.Builder(this)
            .setTitle("Connect to sender")
            .setMessage("Scan the sender's QR code to fill the IP and PIN automatically, or enter them manually. Both phones must use the same trusted Wi-Fi.")
            .setView(form)
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Scan sender QR", (dialog, which) -> {
                dialog.dismiss();
                startQrScan();
            })
            .setPositiveButton("Check PIN", (d, w) -> {
                receiveIp = ip.getText().toString().trim();
                receivePin = pinInput.getText().toString().trim();
                if (receiveIp.isEmpty() || !receivePin.matches("\\d{6}")) {
                    Toast.makeText(this, "Enter a sender IP and 6-digit PIN.", Toast.LENGTH_LONG).show();
                    return;
                }
                io.execute(this::checkSender);
            }).show();
    }

    private void checkSender() {
        try (Socket s = new Socket()) {
            s.connect(new java.net.InetSocketAddress(receiveIp, PORT), 5000);
            s.setSoTimeout(8000);
            OutputStream out = s.getOutputStream();
            out.write(("GET /info?pin=" + receivePin + " HTTP/1.1\r\nHost: " + receiveIp
                + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            String status = r.readLine();
            if (status == null || !status.contains("200")) throw new Exception("PIN rejected or sender unavailable.");
            String h;
            while ((h = r.readLine()) != null && !h.isEmpty()) { }
            StringBuilder body = new StringBuilder();
            while ((h = r.readLine()) != null) body.append(h).append("\n");
            String name = "shared-file";
            long size = -1;
            for (String row : body.toString().split("\n")) {
                if (row.startsWith("name=")) {
                    byte[] decoded = android.util.Base64.decode(row.substring(5),
                        android.util.Base64.URL_SAFE | android.util.Base64.NO_WRAP | android.util.Base64.NO_PADDING);
                    name = new String(decoded, StandardCharsets.UTF_8);
                } else if (row.startsWith("size=")) {
                    try { size = Long.parseLong(row.substring(5)); } catch (Exception ignored) { }
                }
            }
            expectedName = name;
            expectedSize = size;
            final String shownName = name;
            final long shownSize = size;
            runOnUiThread(() -> new AlertDialog.Builder(this)
                .setTitle("Accept incoming file?")
                .setMessage("File: " + shownName + "\nSize: " + readableSize(shownSize)
                    + "\n\nOnly continue if you trust the sender and are on a trusted Wi-Fi network.")
                .setNegativeButton("Decline", null)
                .setPositiveButton("Choose save location", (dialog, which) -> chooseSaveLocation(shownName))
                .show());
        } catch (Exception e) {
            runOnUiThread(() -> Toast.makeText(this, "Connection failed: " + e.getMessage(),
                Toast.LENGTH_LONG).show());
        }
    }

    private void chooseSaveLocation(String name) {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/octet-stream");
        intent.putExtra(Intent.EXTRA_TITLE, name);
        startActivityForResult(intent, SAVE_FILE);
    }

    private void downloadTo(Uri destination) {
        try (Socket s = new Socket()) {
            s.connect(new java.net.InetSocketAddress(receiveIp, PORT), 5000);
            s.setSoTimeout(15000);
            OutputStream out = s.getOutputStream();
            out.write(("GET /download?pin=" + receivePin + " HTTP/1.1\r\nHost: " + receiveIp
                + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            InputStream in = s.getInputStream();
            ByteArrayOutputStream headerBytes = new ByteArrayOutputStream();
            int matched = 0;
            while (headerBytes.size() < 16384) {
                int b = in.read();
                if (b < 0) throw new Exception("Connection closed before transfer.");
                headerBytes.write(b);
                if ((matched == 0 && b == '\r') || (matched == 2 && b == '\r')) matched++;
                else if ((matched == 1 && b == '\n') || (matched == 3 && b == '\n')) matched++;
                else matched = b == '\r' ? 1 : 0;
                if (matched == 4) break;
            }
            String headers = headerBytes.toString("US-ASCII");
            if (!headers.startsWith("HTTP/1.1 200")) throw new Exception("PIN expired, invalid, or already used.");
            long contentLength = -1;
            for (String line : headers.split("\r\n")) {
                if (line.toLowerCase(Locale.US).startsWith("content-length:")) {
                    try { contentLength = Long.parseLong(line.substring(line.indexOf(':') + 1).trim()); } catch (Exception ignored) { }
                }
            }
            OutputStream file = getContentResolver().openOutputStream(destination);
            if (file == null) throw new Exception("Could not open destination file.");
            byte[] buffer = new byte[32 * 1024];
            long total = 0;
            int n;
            while ((n = in.read(buffer)) != -1) {
                file.write(buffer, 0, n);
                total += n;
            }
            file.flush();
            file.close();
            if (contentLength >= 0 && total != contentLength) throw new Exception("Size mismatch; received " + total + " of " + contentLength + " bytes.");
            final long done = total;
            runOnUiThread(() -> Toast.makeText(this, "File received (" + readableSize(done) + ").", Toast.LENGTH_LONG).show());
        } catch (Exception e) {
            runOnUiThread(() -> Toast.makeText(this, "Download failed: " + e.getMessage(), Toast.LENGTH_LONG).show());
        }
    }

    private void stopServer() {
        serverRunning = false;
        if (serverSocket != null) {
            try { serverSocket.close(); } catch (Exception ignored) { }
            serverSocket = null;
        }
    }

    @Override
    protected void onDestroy() {
        stopServer();
        io.shutdownNow();
        super.onDestroy();
    }
}
