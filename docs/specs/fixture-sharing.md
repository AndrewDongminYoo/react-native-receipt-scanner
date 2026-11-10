# Example OCR Fixture Sharing

The example app prepares a detached OCR snapshot after the contributor selects a category.
The snapshot copies text and confidence; it retains no reference to the original receipt image.
It includes no image URI, EXIF, or image bytes.

Before any external share, the contributor edits OCR text inside the app and records anonymization work in notes.
Remove personal names, telephone/payment/tax/transaction/loyalty identifiers, precise addresses, and identifying branch names from both fields.
Use synthetic receipts whenever possible; there is no automatic PII detection or anonymization guarantee.
Replace sensitive spans with stable placeholders such as `XXX` while preserving line breaks needed by the regression.

The app derives both `tokens` and `lines` from the edited text and displays the complete outgoing JSON.
Sharing requires nonblank text, nonblank notes, and an explicit confirmation that the final JSON has been reviewed.
Editing either field resets that confirmation.
The share handler enforces the same conditions as the button and sends exactly the previewed JSON, without rereading the scan result.
Cancelling the editor discards its draft without calling `Share.share`; re-entering starts a fresh unreviewed snapshot.
Cancelling the platform share sheet does not imply data deletion by a selected external application.

The JSON retains the example's category/platform/source/transform/confidence fields.
This export is candidate material for a regression test, not the versioned shared OCR contract corpus.
Review it again before committing it to a suitable test fixture location.
Do not copy captured receipts into `src/__tests__/fixtures/ocr-contracts/v1/`; that corpus remains synthetic and follows its own maintenance guide.

## Verification

`src/__tests__/fixtureReview.test.ts` exercises the real review reducer and sharing function with synthetic private markers and a mocked `Share.share`.
It checks incomplete/cancelled review, edited payload equality, completion invalidation, blank fields, and isolation from later source mutations.
Rendered review checks must additionally cover scrolling, multiline editing, cancellation, and share-button state.
A web rendering of the editor does not verify native keyboards or the iOS/Android share sheets.
