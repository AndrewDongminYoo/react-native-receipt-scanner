# Phase 14: Review OCR Before Fixture Sharing

Issue [#35](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/issues/35) and [the fixture sharing contract](../specs/fixture-sharing.md) define the accepted scope.
The production scanning API and native implementation remain outside this change.

1. Replace the example's warning-to-share shortcut with an in-app editor over copied OCR values.
2. Derive the preview and outgoing payload from edited text and notes, and invalidate explicit completion after every edit.
3. Exercise the sharing boundary with synthetic markers, including incomplete/cancelled drafts and source-object mutation.
4. Correct the fixture collection instructions and stale references in the changed flow.
5. Run typecheck, lint, Jest, scoped Trunk formatting, and Trunk checks; capture a rendered editor for operator visual review before merge.

The regression must fail when the completion guard is removed or editing retains confirmation.
Native share-sheet and keyboard behavior requires separate device/simulator verification; the mocked boundary tests do not establish those properties.
