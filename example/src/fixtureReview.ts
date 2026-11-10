import { Share } from "react-native";

type FixtureInput = { ocrText?: string; ocrQuality?: { confidence?: number } };
export type FixtureCategory = "normal" | "mirrored";
export type FixtureReview = {
  id: string;
  platform: "ios" | "android";
  source: "camera" | "gallery";
  transform: "none" | "horizontalFlip";
  confidence: number | null;
  text: string;
  notes: string;
  reviewed: boolean;
};
type Action = { type: "text" | "notes"; value: string } | { type: "reviewed"; value: boolean };

export function createFixtureReview(
  image: FixtureInput,
  source: FixtureReview["source"],
  category: FixtureCategory,
  tag: string,
  platform: FixtureReview["platform"]
): FixtureReview {
  return {
    id: `${category}-${tag}`,
    platform,
    source,
    transform: category === "mirrored" ? "horizontalFlip" : "none",
    confidence: image.ocrQuality?.confidence ?? null,
    text: image.ocrText ?? "",
    notes: "",
    reviewed: false,
  };
}

export function fixtureReviewReducer(state: FixtureReview, action: Action): FixtureReview {
  return action.type === "reviewed"
    ? { ...state, reviewed: action.value }
    : { ...state, [action.type]: action.value, reviewed: false };
}

export function fixturePayload(state: FixtureReview): string {
  const { text } = state;
  return JSON.stringify(
    {
      id: state.id,
      platform: state.platform,
      source: state.source,
      transform: state.transform,
      confidence: state.confidence,
      notes: state.notes,
      tokens: text.split(/\s+/).filter(Boolean),
      lines: text
        .split(/\n+/)
        .map((line) => line.trim())
        .filter(Boolean),
    },
    null,
    2
  );
}

export function canShareFixture(state: FixtureReview): boolean {
  return state.reviewed && !!state.text.trim() && !!state.notes.trim();
}

export async function shareReviewedFixture(state: FixtureReview | null): Promise<void> {
  if (!state || !canShareFixture(state)) return;
  await Share.share({ title: `${state.id}.json`, message: fixturePayload(state) });
}
