# Trading Agent V3 example

This example is the native Android counterpart to the service-AX interaction prototype. It runs the same app-defined tools in-process and renders `UiFrame` results with the optional Compose host from the SDK.

The market feed uses Binance public Spot endpoints when available and falls back to bundled fixtures. Orders are process-local simulations and never reach an exchange.

The bundled planner is deliberately offline and deterministic so the full mobile experience can be evaluated without provider credentials. It is replaceable: a production in-app agent should give the runtime's `ToolSpec` manifest to its model gateway and execute the selected call through the same `ToolRuntime`.

## Run

```bash
cd examples/trading-agent/android
ANDROID_HOME=/opt/homebrew/share/android-commandlinetools ./gradlew installDebug
adb shell am start -n io.github.pumpkinredbean.appagent.example.tradingv3/.MainActivity
```

Try prompts such as:

- `지금 수익률 확인해줘` — factual `get_*` result; no new UI frame
- `비트코인 상세 보여줘` — live, turn-scoped market frame
- `포트폴리오 보여줘`
- `1,000 USDT를 추가한다면 비교해줘`
- `BTC 500 USDT 매수 준비해줘`

The order remains local. The secure keypad value stays inside the approval UI, and only the final approval boolean reaches the tool runtime.

## What this sample demonstrates

- ordinary `get_*` results stay in the conversation and do not open UI;
- `visualize_*` and review tools return app-defined frames;
- a new user turn pauses the previous live frame, while Resume explicitly reconnects it;
- `tag + params` provides a deduplicated mobile-style back stack;
- frame buttons navigate inside the service UI without forcing a chat transition;
- one approval consumer shows queued actions sequentially, and only the visible action starts its 60-second timer;
- secure-keypad values never become tool arguments or model context.
