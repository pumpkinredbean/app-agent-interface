export class UiAutomatorBridgeError extends Error {
  constructor(message, { code = "UI_DRIVER_ERROR", status = 0, body } = {}) {
    super(message);
    this.name = "UiAutomatorBridgeError";
    this.code = code;
    this.status = status;
    this.body = body;
  }
}

export class UiAutomatorBridgeClient {
  constructor(baseUrl = process.env.ANDROID_UI_BASE_URL ?? "http://127.0.0.1:18766") {
    this.baseUrl = baseUrl.replace(/\/$/, "");
  }

  health() {
    return this.#json("/healthz", {}, 2_000);
  }

  hierarchy() {
    return this.#json("/v1/hierarchy", {}, 12_000);
  }

  screenshot() {
    return this.#json("/v1/screenshot", {}, 15_000);
  }

  action(action) {
    return this.#json("/v1/actions", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(action),
    }, 20_000);
  }

  shutdown() {
    return this.#json("/shutdown", { method: "POST" }, 2_000);
  }

  async #json(path, options = {}, timeoutMs) {
    let response;
    try {
      response = await fetch(`${this.baseUrl}${path}`, {
        ...options,
        signal: AbortSignal.timeout(timeoutMs),
      });
    } catch (error) {
      throw new UiAutomatorBridgeError(`Cannot connect to the Android UI driver: ${error.message}`, {
        code: "UI_DRIVER_UNAVAILABLE",
      });
    }

    const text = await response.text();
    let body;
    try {
      body = JSON.parse(text);
    } catch {
      throw new UiAutomatorBridgeError(`Android UI driver returned non-JSON HTTP ${response.status}`, {
        code: "UI_DRIVER_INVALID_RESPONSE",
        status: response.status,
      });
    }
    if (!response.ok) {
      throw new UiAutomatorBridgeError(body?.error?.message ?? `Android UI driver HTTP ${response.status}`, {
        code: body?.error?.code ?? "UI_DRIVER_REQUEST_FAILED",
        status: response.status,
        body,
      });
    }
    return body;
  }
}
