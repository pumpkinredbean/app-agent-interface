package io.github.pumpkinredbean.appagent.uidriver;

import static org.junit.Assert.assertTrue;

import android.os.Bundle;
import android.os.SystemClock;
import android.util.Base64;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@RunWith(AndroidJUnit4.class)
public final class UiAutomatorDriverServerTest {
    private static final int DEFAULT_PORT = 8766;
    private static final int MAX_HEADER_BYTES = 64 * 1024;
    private static final int MAX_BODY_BYTES = 1024 * 1024;

    @Test
    public void serve() throws Exception {
        Bundle arguments = InstrumentationRegistry.getArguments();
        String targetPackage = requireText(arguments.getString("targetPackage"), "targetPackage");
        int port = parsePort(arguments.getString("port"));
        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());

        try (DriverServer server = new DriverServer(device, targetPackage, port)) {
            server.serve();
        }
        assertTrue(true);
    }

    private static final class DriverServer implements Closeable {
        private final UiDevice device;
        private final String targetPackage;
        private final ServerSocket serverSocket;
        private volatile boolean running = true;

        DriverServer(UiDevice device, String targetPackage, int port) throws IOException {
            this.device = device;
            this.targetPackage = targetPackage;
            this.serverSocket = new ServerSocket(port, 8, InetAddress.getByName("127.0.0.1"));
        }

        void serve() throws IOException {
            while (running) {
                try {
                    Socket socket = serverSocket.accept();
                    handle(socket);
                } catch (IOException error) {
                    if (running) throw error;
                }
            }
        }

        private void handle(Socket socket) {
            try (Socket client = socket) {
                client.setSoTimeout(20_000);
                Request request = readRequest(client.getInputStream());
                Response response;
                try {
                    response = route(request);
                } catch (DriverException error) {
                    response = jsonResponse(error.status, errorBody(error.code, error.getMessage()));
                } catch (Exception error) {
                    response = jsonResponse(500, errorBody("DRIVER_ERROR", safeMessage(error)));
                }
                writeResponse(client.getOutputStream(), response);
            } catch (Exception ignored) {
                // The desktop client owns retries and exposes transport failures to the agent.
            }
        }

        private Response route(Request request) throws Exception {
            if (request.method.equals("GET") && request.path.equals("/healthz")) {
                return jsonResponse(200, baseState()
                    .put("ready", true)
                    .put("runtime", "androidx-uiautomator2"));
            }
            if (request.method.equals("GET") && request.path.equals("/v1/hierarchy")) {
                device.waitForIdle(750);
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                device.dumpWindowHierarchy(output);
                return jsonResponse(200, baseState()
                    .put("xml", new String(output.toByteArray(), StandardCharsets.UTF_8)));
            }
            if (request.method.equals("GET") && request.path.equals("/v1/screenshot")) {
                assertTargetPackage();
                File cacheDirectory = InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir();
                if (!cacheDirectory.exists() && !cacheDirectory.mkdirs()) {
                    throw new DriverException(500, "SCREENSHOT_FAILED", "Could not create the UI driver cache directory");
                }
                File file = File.createTempFile(
                    "app-agent-interface-screen-",
                    ".png",
                    cacheDirectory
                );
                try {
                    if (!device.takeScreenshot(file, 1.0f, 90)) {
                        throw new DriverException(500, "SCREENSHOT_FAILED", "UiAutomator could not capture the display");
                    }
                    assertTargetPackage();
                    String data = Base64.encodeToString(readFile(file), Base64.NO_WRAP);
                    return jsonResponse(200, baseState()
                        .put("mediaType", "image/png")
                        .put("data", data));
                } finally {
                    file.delete();
                }
            }
            if (request.method.equals("POST") && request.path.equals("/v1/actions")) {
                JSONObject action = new JSONObject(request.body);
                return jsonResponse(200, perform(action));
            }
            if (request.method.equals("POST") && request.path.equals("/shutdown")) {
                running = false;
                serverSocket.close();
                return jsonResponse(200, new JSONObject().put("stopped", true));
            }
            throw new DriverException(404, "ROUTE_NOT_FOUND", "Unknown UI driver route");
        }

        private JSONObject perform(JSONObject action) throws Exception {
            assertTargetPackage();
            String type = action.getString("action");
            JSONObject result = baseState().put("action", type);

            switch (type) {
                case "tap" -> {
                    int x = coordinate(action, "x", device.getDisplayWidth());
                    int y = coordinate(action, "y", device.getDisplayHeight());
                    if (!device.click(x, y)) throw actionFailed("Tap failed");
                    result.put("performed", true);
                }
                case "long_press" -> {
                    int x = coordinate(action, "x", device.getDisplayWidth());
                    int y = coordinate(action, "y", device.getDisplayHeight());
                    int durationMs = bounded(action.optInt("durationMs", 650), 400, 3_000, "durationMs");
                    int steps = Math.max(80, durationMs / 5);
                    if (!device.swipe(x, y, x, y, steps)) throw actionFailed("Long press failed");
                    result.put("performed", true).put("durationMs", durationMs);
                }
                case "multi_tap" -> {
                    JSONArray positions = action.getJSONArray("positions");
                    if (positions.length() < 2 || positions.length() > 64) {
                        throw new DriverException(400, "INVALID_ACTION", "multi_tap requires between 2 and 64 positions");
                    }
                    int intervalMs = bounded(action.optInt("intervalMs", 80), 0, 1_000, "intervalMs");
                    int[] xCoordinates = new int[positions.length()];
                    int[] yCoordinates = new int[positions.length()];
                    Set<String> uniquePositions = new HashSet<>();
                    for (int index = 0; index < positions.length(); index += 1) {
                        JSONObject point = positions.getJSONObject(index);
                        int x = coordinate(point, "x", device.getDisplayWidth());
                        int y = coordinate(point, "y", device.getDisplayHeight());
                        if (!uniquePositions.add(x + "," + y)) {
                            throw new DriverException(400, "INVALID_ACTION", "multi_tap positions must be different");
                        }
                        xCoordinates[index] = x;
                        yCoordinates[index] = y;
                    }
                    int completed = 0;
                    for (int index = 0; index < positions.length(); index += 1) {
                        assertTargetPackage();
                        if (!device.click(xCoordinates[index], yCoordinates[index])) {
                            result.put("requested", positions.length()).put("completed", completed).put("failedIndex", index);
                            throw new DriverException(409, "ACTION_FAILED", result.toString());
                        }
                        completed += 1;
                        if (intervalMs > 0 && index + 1 < positions.length()) SystemClock.sleep(intervalMs);
                    }
                    result.put("performed", true).put("requested", positions.length()).put("completed", completed);
                }
                case "input_text" -> {
                    int x = coordinate(action, "x", device.getDisplayWidth());
                    int y = coordinate(action, "y", device.getDisplayHeight());
                    String text = action.getString("text");
                    if (text.length() > 4_000) throw new DriverException(400, "INVALID_ACTION", "Input text is too long");
                    if (!device.click(x, y)) throw actionFailed("Could not focus the text field");
                    SystemClock.sleep(150);
                    UiObject2 focused = device.findObject(By.focused(true));
                    if (focused == null) throw actionFailed("No focused UI element accepts text");
                    focused.setText(text);
                    result.put("performed", true).put("characterCount", text.length());
                }
                case "swipe" -> {
                    int startX = coordinate(action, "startX", device.getDisplayWidth());
                    int startY = coordinate(action, "startY", device.getDisplayHeight());
                    int endX = coordinate(action, "endX", device.getDisplayWidth());
                    int endY = coordinate(action, "endY", device.getDisplayHeight());
                    int durationMs = bounded(action.optInt("durationMs", 350), 50, 5_000, "durationMs");
                    int steps = Math.max(2, durationMs / 16);
                    if (!device.swipe(startX, startY, endX, endY, steps)) throw actionFailed("Swipe failed");
                    result.put("performed", true);
                }
                case "press" -> {
                    String key = action.getString("key").toUpperCase(Locale.ROOT);
                    boolean performed = switch (key) {
                        case "BACK" -> device.pressBack();
                        case "ENTER" -> device.pressEnter();
                        default -> throw new DriverException(400, "INVALID_ACTION", "Unsupported key: " + key);
                    };
                    if (!performed) throw actionFailed("Key press failed");
                    result.put("performed", true).put("key", key);
                }
                default -> throw new DriverException(400, "INVALID_ACTION", "Unsupported action: " + type);
            }

            device.waitForIdle(750);
            return result;
        }

        private JSONObject baseState() throws Exception {
            return new JSONObject()
                .put("targetPackage", targetPackage)
                .put("currentPackage", nullable(device.getCurrentPackageName()))
                .put("width", device.getDisplayWidth())
                .put("height", device.getDisplayHeight())
                .put("rotation", device.getDisplayRotation());
        }

        private void assertTargetPackage() throws DriverException {
            String current = device.getCurrentPackageName();
            if (!targetPackage.equals(current)) {
                throw new DriverException(
                    409,
                    "OUTSIDE_TARGET_APP",
                    "Expected foreground package " + targetPackage + " but found " + nullable(current)
                );
            }
        }

        @Override
        public void close() throws IOException {
            running = false;
            serverSocket.close();
        }
    }

    private static Request readRequest(InputStream input) throws IOException {
        ByteArrayOutputStream headers = new ByteArrayOutputStream();
        int state = 0;
        while (headers.size() < MAX_HEADER_BYTES) {
            int value = input.read();
            if (value < 0) throw new IOException("Connection closed before headers");
            headers.write(value);
            state = switch (state) {
                case 0 -> value == '\r' ? 1 : 0;
                case 1 -> value == '\n' ? 2 : 0;
                case 2 -> value == '\r' ? 3 : 0;
                case 3 -> value == '\n' ? 4 : 0;
                default -> state;
            };
            if (state == 4) break;
        }
        if (state != 4) throw new IOException("HTTP headers are too large");

        String[] lines = new String(headers.toByteArray(), StandardCharsets.ISO_8859_1).split("\r\n");
        String[] requestLine = lines[0].split(" ");
        if (requestLine.length < 2) throw new IOException("Invalid request line");
        Map<String, String> headerMap = new LinkedHashMap<>();
        for (int index = 1; index < lines.length; index += 1) {
            int separator = lines[index].indexOf(':');
            if (separator > 0) {
                headerMap.put(
                    lines[index].substring(0, separator).trim().toLowerCase(Locale.ROOT),
                    lines[index].substring(separator + 1).trim()
                );
            }
        }
        int contentLength = parseContentLength(headerMap.get("content-length"));
        byte[] body = readExactly(input, contentLength);
        return new Request(
            requestLine[0].toUpperCase(Locale.ROOT),
            requestLine[1].split("\\?", 2)[0],
            new String(body, StandardCharsets.UTF_8)
        );
    }

    private static void writeResponse(OutputStream output, Response response) throws IOException {
        byte[] body = response.body.getBytes(StandardCharsets.UTF_8);
        String reason = switch (response.status) {
            case 200 -> "OK";
            case 400 -> "Bad Request";
            case 404 -> "Not Found";
            case 409 -> "Conflict";
            default -> "Error";
        };
        String headers = "HTTP/1.1 " + response.status + " " + reason + "\r\n"
            + "Content-Type: application/json; charset=utf-8\r\n"
            + "Content-Length: " + body.length + "\r\n"
            + "Cache-Control: no-store\r\n"
            + "Connection: close\r\n\r\n";
        output.write(headers.getBytes(StandardCharsets.ISO_8859_1));
        output.write(body);
        output.flush();
    }

    private static byte[] readExactly(InputStream input, int length) throws IOException {
        byte[] result = new byte[length];
        int offset = 0;
        while (offset < length) {
            int count = input.read(result, offset, length - offset);
            if (count < 0) throw new IOException("Connection closed before request body");
            offset += count;
        }
        return result;
    }

    private static byte[] readFile(File file) throws IOException {
        try (InputStream input = new FileInputStream(file); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16 * 1024];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (count > 0) output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    private static Response jsonResponse(int status, JSONObject body) {
        return new Response(status, body.toString());
    }

    private static JSONObject errorBody(String code, String message) {
        try {
            return new JSONObject().put(
                "error",
                new JSONObject().put("code", code).put("message", message)
            );
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static DriverException actionFailed(String message) {
        return new DriverException(409, "ACTION_FAILED", message);
    }

    private static int coordinate(JSONObject object, String name, int limit) throws DriverException {
        if (!object.has(name)) throw new DriverException(400, "INVALID_ACTION", "Missing coordinate: " + name);
        return bounded(object.optInt(name, -1), 0, Math.max(0, limit - 1), name);
    }

    private static int bounded(int value, int minimum, int maximum, String name) throws DriverException {
        if (value < minimum || value > maximum) {
            throw new DriverException(400, "INVALID_ACTION", name + " must be between " + minimum + " and " + maximum);
        }
        return value;
    }

    private static int parsePort(String value) {
        if (value == null || value.isBlank()) return DEFAULT_PORT;
        int port = Integer.parseInt(value);
        if (port < 1 || port > 65_535) throw new IllegalArgumentException("port is outside the valid range");
        return port;
    }

    private static int parseContentLength(String value) throws IOException {
        if (value == null || value.isBlank()) return 0;
        try {
            int length = Integer.parseInt(value);
            if (length < 0 || length > MAX_BODY_BYTES) throw new IOException("Invalid Content-Length");
            return length;
        } catch (NumberFormatException error) {
            throw new IOException("Invalid Content-Length", error);
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value;
    }

    private static String nullable(String value) {
        return value == null ? "" : value;
    }

    private static String safeMessage(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    private record Request(String method, String path, String body) {}
    private record Response(int status, String body) {}

    private static final class DriverException extends Exception {
        final int status;
        final String code;

        DriverException(int status, String code, String message) {
            super(message);
            this.status = status;
            this.code = code;
        }
    }
}
