export type AndroidUiToolErrorCode =
  | "OUTSIDE_TARGET_APP"
  | "STALE_UI_SNAPSHOT"
  | "UI_ACTION_UNSUPPORTED"
  | "UI_CONTROL_DISABLED"
  | "UI_CONTROL_NOT_FOUND"
  | "UI_SNAPSHOT_NOT_FOUND"
  | "UI_TOOL_FAILED"
  | "UI_TOOL_NOT_FOUND"
  | "UI_WAIT_TIMEOUT";

export class AndroidUiToolError extends Error {
  public readonly code: AndroidUiToolErrorCode;

  public constructor(code: AndroidUiToolErrorCode, message: string, options?: ErrorOptions) {
    super(message, options);
    this.name = "AndroidUiToolError";
    this.code = code;
  }
}
