import type { UiCandidate, UiControlRole, UiNodeRole } from "./types.js";

export function roleFor(className: string): UiNodeRole {
  const name = shortClassName(className);
  if (/EditText|TextInputEditText/.test(name)) return "text_field";
  if (/ImageButton/.test(name)) return "image_button";
  if (/Button/.test(name)) return "button";
  if (/CheckBox/.test(name)) return "checkbox";
  if (/RadioButton/.test(name)) return "radio";
  if (/Switch|ToggleButton/.test(name)) return "switch";
  if (/Spinner/.test(name)) return "select";
  if (/SeekBar/.test(name)) return "slider";
  if (/RecyclerView|ListView/.test(name)) return "list";
  if (/ScrollView/.test(name)) return "scroll_view";
  if (/WebView/.test(name)) return "web_view";
  if (/TextView/.test(name)) return "text";
  if (/ImageView/.test(name)) return "image";
  return "view";
}

export function controlRole(candidate: UiCandidate): UiControlRole {
  if (candidate.role === "text_field") return "text_field";
  if (
    candidate.role === "checkbox"
    || candidate.role === "radio"
    || candidate.role === "switch"
    || candidate.role === "select"
    || candidate.role === "slider"
    || candidate.role === "button"
    || candidate.role === "image_button"
  ) {
    return candidate.role;
  }
  if (candidate.raw.checkable) return "checkbox";
  if (candidate.raw.scrollable && !candidate.raw.clickable && !candidate.raw.checkable) return "scroll_area";
  if (candidate.raw.clickable || candidate.raw.checkable) return "button";
  return "control";
}

export function shortClassName(value: string): string {
  return value.split(".").at(-1) ?? value;
}
