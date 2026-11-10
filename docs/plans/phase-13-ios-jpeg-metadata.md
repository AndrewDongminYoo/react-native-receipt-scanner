# iOS JPEG Metadata Allowlist (#29)

## Contract

Rebuild the output JPEG's EXIF and TIFF dictionaries from the tags represented by the normalized public API.
Keep `includeRawExif` as a JavaScript exposure option, independent of file retention.
`includeExif: false` omits source metadata and the JS `exif` field; output orientation remains `1`.
GPS retains its existing explicit opt-in policy, subordinate to `includeExif`.
Camera scans keep their existing synthetic JS metadata behavior.
The canonical file policy belongs in `../specs/scan-pipeline.md`.

## Implementation

1. Add a simulator reader under `scripts/apple-metadata/` that builds synthetic JPEGs, invokes the production `RNImageProcessor`, and reads encoded metadata back through ImageIO.
   Cover all EXIF/GPS/raw flag combinations, original orientation, and rotation followed by re-encoding.
   Observe unexpected source tags surviving before changing the encoder.
2. Restrict source EXIF/TIFF copying in `ios/RNImageProcessor.m` without changing the public API or raw JS map.
   Update the header, option comments, README, and affected metadata contracts.
3. Run the reader in the existing native iOS workflow, complete the declared JS/Trunk checks, build and run the example on an iOS simulator, and review the final candidate.

## Verification

- `bash scripts/apple-metadata/run.sh <booted-simulator-uuid>` checks actual file tags and the dictionary forwarded to JS.
- `yarn typecheck && yarn lint && yarn test`, scoped `trunk fmt`, and `trunk check` cover the declared repository gates.
- `yarn example ios --udid <simulator-uuid> --no-packager` verifies integration without writing to a physical device.
- Review the final diff for excluded metadata leaks, retained API fields, option precedence, and documentation drift.

Scanner UI interaction and real-photo OCR accuracy are outside this synthetic metadata regression.
