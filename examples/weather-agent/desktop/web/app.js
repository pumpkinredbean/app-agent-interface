const elements = {
  conversation: document.querySelector("#conversation"),
  messages: document.querySelector("#messages"),
  form: document.querySelector("#chat-form"),
  input: document.querySelector("#message-input"),
  send: document.querySelector("#send-button"),
  newChat: document.querySelector("#new-chat"),
  provider: document.querySelector("#provider"),
  model: document.querySelector("#model"),
  modelOptions: document.querySelector("#model-options"),
  providerName: document.querySelector("#provider-name"),
  providerDetail: document.querySelector("#provider-detail"),
  headerProvider: document.querySelector("#header-provider"),
  desktopDot: document.querySelector("#desktop-dot"),
  desktopStatus: document.querySelector("#desktop-status"),
  androidDot: document.querySelector("#android-dot"),
  androidStatus: document.querySelector("#android-status"),
  uiDriverDot: document.querySelector("#ui-driver-dot"),
  uiDriverStatus: document.querySelector("#ui-driver-status"),
  capabilityCount: document.querySelector("#capability-count"),
  toast: document.querySelector("#toast"),
};

const PROVIDER_COPY = {
  codex: { name: "Codex", detail: "Uses your ChatGPT subscription", header: "Codex agent" },
  copilot: { name: "GitHub Copilot", detail: "Uses your Copilot subscription", header: "Copilot agent" },
  claude: { name: "Claude", detail: "Uses the Agent SDK", header: "Claude agent" },
};

const state = {
  sessionId: crypto.randomUUID(),
  busy: false,
  providerStatus: [],
};

const tomorrow = new Date();
tomorrow.setDate(tomorrow.getDate() + 1);
const tomorrowText = new Intl.DateTimeFormat("en-CA").format(tomorrow);
const alertSuggestion = document.querySelector("#alert-suggestion");
alertSuggestion.dataset.prompt = `Alert me if the precipitation probability in Seoul reaches 70% on ${tomorrowText}.`;

elements.form.addEventListener("submit", (event) => {
  event.preventDefault();
  sendMessage(elements.input.value);
});

elements.input.addEventListener("input", resizeInput);
elements.input.addEventListener("keydown", (event) => {
  if (event.key === "Enter" && !event.shiftKey) {
    event.preventDefault();
    elements.form.requestSubmit();
  }
});

elements.provider.addEventListener("change", () => {
  elements.model.value = "";
  updateProviderCopy();
  const selected = state.providerStatus.find((provider) => provider.id === elements.provider.value);
  if (selected && !selected.configured) {
    showToast(selected.id === "claude"
      ? "The Claude adapter is implemented, but ANTHROPIC_API_KEY is not configured."
      : `${selected.label} may require authentication.`);
  }
});

elements.newChat.addEventListener("click", resetConversation);
document.addEventListener("keydown", (event) => {
  if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === "k") {
    event.preventDefault();
    resetConversation();
  }
});

for (const button of document.querySelectorAll("[data-prompt]")) {
  button.addEventListener("click", () => sendMessage(button.dataset.prompt));
}

checkStatus();
updateProviderCopy();
elements.input.focus();

async function checkStatus() {
  try {
    const response = await fetch("/api/status", { cache: "no-store" });
    if (!response.ok) throw new Error("status failed");
    const status = await response.json();
    state.providerStatus = status.providers ?? [];
    updateProviderCopy();
    setConnection(elements.desktopDot, elements.desktopStatus, status.agent.ready, "Running locally");
    setConnection(
      elements.androidDot,
      elements.androidStatus,
      status.app.ready,
      status.app.ready ? `${status.app.label} · ${status.app.toolCount} tools` : "Not connected",
    );
    const uiDriver = status.uiDriver ?? { enabled: false, ready: false, toolCount: 0 };
    setConnection(
      elements.uiDriverDot,
      elements.uiDriverStatus,
      uiDriver.ready,
      !uiDriver.enabled
        ? "Disabled"
        : uiDriver.ready
          ? `${uiDriver.runtime ?? "UiAutomator2"} · ${uiDriver.toolCount} tools`
          : "Not connected · semantic app tools still available",
      !uiDriver.enabled,
    );
    elements.capabilityCount.textContent = `${status.app.toolCount ?? 0} APP · ${uiDriver.ready ? uiDriver.toolCount : 0} UI TOOLS`;
  } catch {
    setConnection(elements.desktopDot, elements.desktopStatus, false, "Status check failed");
    setConnection(elements.androidDot, elements.androidStatus, false, "Check connection");
    setConnection(elements.uiDriverDot, elements.uiDriverStatus, false, "Check connection");
  }
}

function setConnection(dot, label, ready, text, neutral = false) {
  dot.classList.toggle("online", ready);
  dot.classList.toggle("error", !ready && !neutral);
  label.textContent = text;
}

async function sendMessage(rawMessage) {
  const message = rawMessage?.trim();
  if (!message || state.busy) return;

  state.busy = true;
  setComposerBusy(true);
  elements.input.value = "";
  resizeInput();
  appendUserMessage(message);
  const runView = appendAgentRun();

  try {
    const response = await fetch("/api/chat", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        message,
        sessionId: state.sessionId,
        provider: elements.provider.value,
        model: elements.model.value.trim() || undefined,
      }),
    });

    if (!response.ok) {
      const error = await response.json().catch(() => ({}));
      throw new Error(error.error ?? `Request failed (${response.status})`);
    }

    await consumeEventStream(response, (event) => updateRun(runView, event));
  } catch (error) {
    failRun(runView, error.message);
  } finally {
    state.busy = false;
    setComposerBusy(false);
    elements.input.focus();
    checkStatus();
  }
}

async function consumeEventStream(response, onEvent) {
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";

  while (true) {
    const { value, done } = await reader.read();
    buffer += decoder.decode(value ?? new Uint8Array(), { stream: !done });
    const lines = buffer.split("\n");
    buffer = lines.pop() ?? "";
    for (const line of lines) {
      if (line.trim()) onEvent(JSON.parse(line));
    }
    if (done) break;
  }
  if (buffer.trim()) onEvent(JSON.parse(buffer));
}

function appendUserMessage(message) {
  showMessages();
  const article = document.createElement("article");
  article.className = "message user";
  article.innerHTML = `
    <div class="message-avatar" aria-hidden="true">ME</div>
    <div class="message-content">
      <div class="message-meta"><strong>You</strong><span>${currentTime()}</span></div>
    </div>`;
  const text = document.createElement("p");
  text.className = "message-text";
  text.textContent = message;
  article.querySelector(".message-content").append(text);
  elements.messages.append(article);
  scrollToLatest();
}

function appendAgentRun() {
  const article = document.createElement("article");
  article.className = "message assistant";
  article.innerHTML = `
    <div class="message-avatar" aria-hidden="true">W</div>
    <div class="message-content">
      <div class="message-meta"><strong>Weather Agent</strong><span>${currentTime()}</span></div>
      <div class="agent-activity">
          <span class="run-pulse" aria-hidden="true"></span>
          <span class="run-title">Thinking</span>
          <span class="run-provider"></span>
      </div>
      <div class="assistant-notes"></div>
      <div class="tool-events"></div>
      <div class="answer-block">
        <div class="message-text answer-text"></div>
        <div class="answer-foot">
          <span>●</span>
          <span class="answer-source"></span>
          <span class="answer-attributions"></span>
        </div>
      </div>
    </div>`;
  elements.messages.append(article);
  scrollToLatest();
  return {
    root: article,
    activity: article.querySelector(".agent-activity"),
    title: article.querySelector(".run-title"),
    provider: article.querySelector(".run-provider"),
    notes: article.querySelector(".assistant-notes"),
    toolEvents: article.querySelector(".tool-events"),
    toolCards: new Map(),
    answer: article.querySelector(".answer-block"),
    answerText: article.querySelector(".answer-block .message-text"),
    answerSource: article.querySelector(".answer-source"),
    answerAttributions: article.querySelector(".answer-attributions"),
  };
}

function updateRun(view, payload) {
  if (payload.type === "chat.accepted") {
    state.sessionId = payload.sessionId;
    view.provider.textContent = PROVIDER_COPY[payload.provider]?.name ?? payload.provider;
    return;
  }

  if (payload.type === "chat.error") {
    failRun(view, payload.message);
    return;
  }

  if (payload.type === "chat.completed") {
    renderAnswer(view.answerText, payload.answer);
    const toolCallCount = payload.run?.toolCallCount ?? 0;
    view.answerSource.textContent = toolCallCount > 0
      ? `${toolCallCount} Android ToolCall${toolCallCount === 1 ? "" : "s"}`
      : "Direct response · no ToolCall";
    renderAttributions(view.answerAttributions, payload.attributions ?? []);
    view.answer.classList.add("visible");
    view.activity.hidden = true;
    scrollToLatest();
    return;
  }

  if (payload.type !== "agent.event") return;
  switch (payload.event) {
    case "agent.started":
      view.provider.textContent = payload.provider;
      view.title.textContent = "Thinking";
      break;
    case "model.started":
      view.title.textContent = payload.toolCallCount > 0
        ? "Considering the app results"
        : "Thinking";
      break;
    case "assistant.message":
      appendAssistantNote(view, payload.text);
      break;
    case "tool.dispatched":
      appendToolCard(view, payload);
      view.title.textContent = `Running ${payload.toolLabel}`;
      break;
    case "tool.completed":
      completeToolCard(view, payload);
      view.title.textContent = "Considering the app result";
      break;
    case "tool.failed":
      failToolCard(view, payload);
      view.title.textContent = "Considering the app error";
      break;
    case "agent.completed":
      view.title.textContent = `Done · ${formatDuration(payload.durationMs)}`;
      break;
    case "agent.failed":
      failRun(view, payload.message);
      break;
  }
  scrollToLatest();
}

function appendAssistantNote(view, text) {
  const note = document.createElement("div");
  note.className = "message-text assistant-note";
  renderAnswer(note, text);
  view.notes.append(note);
}

function appendToolCard(view, payload) {
  const card = document.createElement("div");
  card.className = "tool-event running";
  card.innerHTML = `
    <span class="tool-event-icon" aria-hidden="true">↗</span>
    <div class="tool-event-content">
      <div class="tool-event-head">
        <strong class="tool-event-name"></strong>
        <span class="tool-event-state">Running on Android</span>
      </div>
      <p class="tool-event-arguments"></p>
      <p class="tool-event-result"></p>
    </div>
    <code class="tool-event-id"></code>`;
  card.querySelector(".tool-event-name").textContent = payload.toolLabel;
  card.querySelector(".tool-event-arguments").textContent = payload.argumentsLabel;
  card.querySelector(".tool-event-id").textContent = `#${payload.requestId}`;
  view.toolEvents.append(card);
  view.toolCards.set(payload.requestId, card);
}

function completeToolCard(view, payload) {
  const card = view.toolCards.get(payload.requestId);
  if (!card) return;
  card.classList.remove("running");
  card.classList.add("complete");
  card.querySelector(".tool-event-icon").textContent = "✓";
  card.querySelector(".tool-event-state").textContent = `Completed · ${formatDuration(payload.durationMs)}`;
  card.querySelector(".tool-event-result").textContent = payload.summary;
}

function failToolCard(view, payload) {
  const card = view.toolCards.get(payload.requestId);
  if (!card) return;
  card.classList.remove("running");
  card.classList.add("error");
  card.querySelector(".tool-event-icon").textContent = "!";
  card.querySelector(".tool-event-state").textContent = `Rejected · ${formatDuration(payload.durationMs)}`;
  card.querySelector(".tool-event-result").textContent = `${payload.code}: ${payload.message}`;
}

function renderAttributions(container, attributions) {
  container.replaceChildren();
  for (const attribution of attributions) {
    if (!attribution?.text || !attribution?.url) continue;
    container.append(document.createTextNode(" · "));
    const link = document.createElement("a");
    link.href = attribution.url;
    link.target = "_blank";
    link.rel = "noreferrer";
    link.textContent = `${attribution.text}${attribution.license ? ` (${attribution.license})` : ""}`;
    container.append(link);
  }
}

function failRun(view, message) {
  view.activity.classList.add("error");
  view.title.textContent = "The request could not be completed";
  view.answerText.textContent = message;
  view.answerText.classList.add("error-text");
  view.answerSource.textContent = "Check the connection or provider configuration";
  view.answer.classList.add("visible");
  scrollToLatest();
}

function renderAnswer(container, answer) {
  container.replaceChildren();
  const lines = String(answer).split(/\r?\n/);
  let list = null;

  for (const line of lines) {
    const bullet = line.match(/^\s*[-•]\s+(.+)/);
    if (bullet) {
      if (!list) {
        list = document.createElement("ul");
        container.append(list);
      }
      const item = document.createElement("li");
      appendInlineText(item, bullet[1]);
      list.append(item);
      continue;
    }

    list = null;
    if (!line.trim()) continue;
    const paragraph = document.createElement("p");
    appendInlineText(paragraph, line);
    container.append(paragraph);
  }
}

function appendInlineText(container, text) {
  const parts = text.split(/(\*\*.*?\*\*)/g);
  for (const part of parts) {
    if (part.startsWith("**") && part.endsWith("**") && part.length > 4) {
      const strong = document.createElement("strong");
      strong.textContent = part.slice(2, -2);
      container.append(strong);
    } else {
      container.append(document.createTextNode(part));
    }
  }
}

function resetConversation() {
  if (state.busy) {
    showToast("Start a new conversation after the current response finishes.");
    return;
  }
  state.sessionId = crypto.randomUUID();
  elements.messages.replaceChildren();
  elements.conversation.classList.remove("has-messages");
  elements.input.value = "";
  resizeInput();
  elements.input.focus();
}

function showMessages() {
  elements.conversation.classList.add("has-messages");
}

function setComposerBusy(busy) {
  elements.send.disabled = busy;
  elements.send.classList.toggle("running", busy);
  elements.input.disabled = busy;
  elements.provider.disabled = busy;
  elements.model.disabled = busy;
}

function updateProviderCopy() {
  const copy = PROVIDER_COPY[elements.provider.value];
  const status = state.providerStatus.find((provider) => provider.id === elements.provider.value);
  elements.providerName.textContent = copy.name;
  elements.providerDetail.textContent = copy.detail;
  elements.headerProvider.textContent = copy.header;
  elements.model.placeholder = status?.defaultModel ? `Default: ${status.defaultModel}` : "Provider default";
  elements.modelOptions.replaceChildren();
  for (const model of status?.models ?? []) {
    const option = document.createElement("option");
    option.value = model;
    elements.modelOptions.append(option);
  }
}

function resizeInput() {
  elements.input.style.height = "auto";
  elements.input.style.height = `${Math.min(elements.input.scrollHeight, 130)}px`;
}

function scrollToLatest() {
  requestAnimationFrame(() => {
    elements.conversation.scrollTo({ top: elements.conversation.scrollHeight, behavior: "smooth" });
  });
}

function currentTime() {
  return new Intl.DateTimeFormat("en-GB", { hour: "2-digit", minute: "2-digit", hour12: false }).format(new Date());
}

function formatDuration(milliseconds = 0) {
  return milliseconds < 1_000 ? `${milliseconds}ms` : `${(milliseconds / 1_000).toFixed(1)}s`;
}

let toastTimer;
function showToast(message) {
  clearTimeout(toastTimer);
  elements.toast.textContent = message;
  elements.toast.classList.add("visible");
  toastTimer = setTimeout(() => elements.toast.classList.remove("visible"), 3_400);
}
