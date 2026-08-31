export type Bounds = readonly [left: number, top: number, right: number, bottom: number];

export type UiNodeRole =
  | "button"
  | "checkbox"
  | "image"
  | "image_button"
  | "list"
  | "radio"
  | "scroll_view"
  | "select"
  | "slider"
  | "switch"
  | "text"
  | "text_field"
  | "view"
  | "web_view";

export type UiControlRole =
  | "button"
  | "checkbox"
  | "control"
  | "image_button"
  | "radio"
  | "scroll_area"
  | "select"
  | "slider"
  | "switch"
  | "text_field";

export type UiControlAction = "long_press" | "scroll" | "set_text" | "tap";

export interface UiHierarchyOptions {
  readonly allowedPackages?: readonly string[];
  readonly maxNodeText?: number;
  readonly maxNodes?: number;
  readonly maxRawNodes?: number;
  readonly maxTotalText?: number;
  readonly maxXmlLength?: number;
  readonly screenHeight?: number;
  readonly screenWidth?: number;
}

export interface UiControlState {
  readonly checked?: boolean;
  readonly enabled?: false;
  readonly focused?: true;
  readonly password?: true;
  readonly selected?: true;
}

export interface UiControl {
  readonly nodeId: string;
  readonly role: UiControlRole;
  readonly label?: string;
  readonly value?: string;
  readonly detail?: string;
  readonly textTruncated?: true;
  readonly resourceId?: string;
  readonly className?: string;
  readonly bounds: Bounds;
  readonly actions: readonly UiControlAction[];
  readonly state?: UiControlState;
}

export interface UiContent {
  readonly role: Exclude<UiNodeRole, "view"> | "text";
  readonly text?: string;
  readonly contentDescription?: string;
  readonly textTruncated?: true;
  readonly resourceId?: string;
  readonly bounds: Bounds;
  readonly state?: { readonly enabled: false };
}

export interface UiHierarchyMetrics {
  readonly rawNodeCount: number;
  readonly includedRawNodeCount: number;
  readonly semanticNodeCount: number;
  readonly contentCount: number;
  readonly controlCount: number;
  readonly omittedNodeCount: number;
  readonly omittedSemanticNodeCount: number;
  readonly maxRawDepth: number;
  readonly truncatedTextNodeCount: number;
}

export interface UiHierarchyProjection {
  readonly content: readonly UiContent[];
  readonly controls: readonly UiControl[];
  readonly fingerprint: string;
  readonly metrics: UiHierarchyMetrics;
  /** Internal action lookup. It is not included in model-facing snapshots. */
  readonly nodeMap: ReadonlyMap<string, ProjectedControlNode>;
}

export interface RawUiNode {
  readonly index: number;
  readonly parentIndex: number | null;
  readonly depth: number;
  readonly children: number[];
  readonly text: string;
  readonly hint: string;
  readonly resourceId: string;
  readonly className: string;
  readonly packageName: string;
  readonly description: string;
  readonly checkable: boolean;
  readonly checked: boolean;
  readonly clickable: boolean;
  readonly enabled: boolean;
  readonly focusable: boolean;
  readonly focused: boolean;
  readonly scrollable: boolean;
  readonly longClickable: boolean;
  readonly password: boolean;
  readonly selected: boolean;
  readonly bounds: Bounds;
  included: boolean;
}

export interface UiCandidate {
  readonly raw: RawUiNode;
  readonly role: UiNodeRole;
  readonly control: boolean;
  readonly labelOwner: boolean;
  readonly editable: boolean;
  readonly actions: readonly UiControlAction[];
  readonly text: string;
  readonly hint: string;
  textTruncated: boolean;
  readonly description: string;
  readonly descriptionTruncated: boolean;
  descendantText: string[];
  descendantDescriptions: string[];
  readonly priority: number;
  readonly maxNodeText: number;
}

export interface ProjectedControlNode extends UiCandidate {
  readonly presented: UiControl;
}
