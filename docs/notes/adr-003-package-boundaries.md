# ADR-003: Package Responsibility Boundaries

## Status

Accepted

## Context

`react-native-receipt-scanner` exists to solve image-acquisition and normalisation problems upstream of Azure OCR.
There is a recurring temptation to add receipt-domain logic (parse the store name, validate the total, check for duplicates) inside the package because it has access to the raw image and OCR text.

## Decision

The package owns **image primitive operations only**:

| In scope                               | Out of scope                                  |
| -------------------------------------- | --------------------------------------------- |
| Image acquisition (camera / gallery)   | Receipt domain parsing (store, amount, date)  |
| Document crop + perspective correction | Upload transport and retry policy             |
| Rotation and orientation normalisation | Azure OCR / Document Intelligence integration |
| JPEG compression at target quality     | Fraud detection                               |
| EXIF extraction and GPS stripping      | Duplicate receipt detection                   |
| On-device OCR text (raw string)        | Point / reward business logic                 |
| Temp file lifecycle                    | Server-side validation rules                  |

The `ocrText` field returns the raw recognized string.
Interpreting that string (is this a valid receipt? what is the total?) is the responsibility of the app layer or the server.

## Consequences

- **Package stays reusable** — a future app with different receipt-validation rules can use the same package without modification.
- **OCR is a primitive** — the package runs on-device OCR for pre-validation and user preview only.
  The authoritative OCR result is still Azure, which runs server-side.
  Client OCR should be used to:
  (a) show a preview to the user,
  (b) gate obviously bad images before upload (blank page, non-text image).
- **Domain leakage is a code-review concern** — any PR that adds receipt parsing, upload logic, or validation rules to this package should be rejected and moved to the app or a separate domain package.
