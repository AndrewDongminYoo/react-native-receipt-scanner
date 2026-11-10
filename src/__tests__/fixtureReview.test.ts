import { afterEach, beforeEach, expect, jest, test } from "@jest/globals";
import { Share } from "react-native";
import {
  createFixtureReview,
  fixtureReviewReducer,
  fixturePayload,
  shareReviewedFixture,
} from "../../example/src/fixtureReview";

const original = () => ({ ocrText: "PRIVATE_MARKER\nTOTAL 100", ocrQuality: { confidence: 0.9 } });

beforeEach(() => {
  jest
    .spyOn(Share, "share")
    .mockResolvedValue({ action: Share.sharedAction, activityType: undefined });
});
afterEach(() => {
  jest.restoreAllMocks();
});

test("incomplete or cancelled review never shares", async () => {
  const draft = createFixtureReview(original(), "gallery", "normal", "test", "ios");
  await shareReviewedFixture(draft);
  await shareReviewedFixture(null);
  expect(Share.share).not.toHaveBeenCalled();
});

test("shares exactly the edited preview, independent of later original mutations", async () => {
  const image = original();
  let draft = createFixtureReview(image, "gallery", "normal", "test", "ios");
  draft = fixtureReviewReducer(draft, { type: "text", value: "XXX\nTOTAL 100" });
  draft = fixtureReviewReducer(draft, { type: "notes", value: "Name replaced with XXX" });
  draft = fixtureReviewReducer(draft, { type: "reviewed", value: true });
  const preview = fixturePayload(draft);
  image.ocrText = "NEW_PRIVATE_MARKER";
  image.ocrQuality.confidence = 0.1;
  await shareReviewedFixture(draft);
  expect(Share.share).toHaveBeenCalledWith({ title: "normal-test.json", message: preview });
  expect(preview).not.toContain("PRIVATE_MARKER");
  expect(JSON.parse(preview)).toMatchObject({
    tokens: ["XXX", "TOTAL", "100"],
    lines: ["XXX", "TOTAL 100"],
    confidence: 0.9,
  });
});

test.each(["text", "notes"] as const)("editing %s invalidates completion", async (type) => {
  let draft = createFixtureReview(original(), "camera", "mirrored", "test", "android");
  draft = fixtureReviewReducer(draft, { type: "notes", value: "Reviewed" });
  draft = fixtureReviewReducer(draft, { type: "reviewed", value: true });
  draft = fixtureReviewReducer(draft, { type, value: "changed" });
  await shareReviewedFixture(draft);
  expect(Share.share).not.toHaveBeenCalled();
});

test("confirmation alone cannot share blank text or notes", async () => {
  let draft = createFixtureReview(original(), "camera", "normal", "test", "ios");
  draft = fixtureReviewReducer(draft, { type: "reviewed", value: true });
  await shareReviewedFixture(draft);
  draft = fixtureReviewReducer(draft, { type: "notes", value: "Reviewed" });
  draft = fixtureReviewReducer(draft, { type: "text", value: "   " });
  draft = fixtureReviewReducer(draft, { type: "reviewed", value: true });
  await shareReviewedFixture(draft);
  expect(Share.share).not.toHaveBeenCalled();
});
