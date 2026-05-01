# react-native-receipt-scanner

Receipt document scanner for the Youngkeul app. Wraps ML Kit (Android) and VisionKit (iOS)
with a unified API for scanning, document crop, JPEG compression, EXIF extraction,
and on-device OCR.

## Installation

```sh
npm install react-native-receipt-scanner
```

## Usage

```ts
import { scan } from 'react-native-receipt-scanner';

const result = await scan({ source: 'camera', ocr: true });

if (result.status === 'success') {
  const { uri, width, height, fileSize, ocrText, exif } = result.images[0];
}
```

## Options

| Option           | Type                    | Default    | Description                                      |
| ---------------- | ----------------------- | ---------- | ------------------------------------------------ |
| `source`         | `'camera' \| 'gallery'` | `'camera'` | Open document scanner camera or image picker     |
| `maxPages`       | `number`                | `1`        | Maximum pages per scan session                   |
| `quality`        | `number` (0.0–1.0)      | `0.82`     | JPEG compression quality after crop              |
| `includeExif`    | `boolean`               | `true`     | Attach EXIF metadata to result                   |
| `includeGpsExif` | `boolean`               | `false`    | Include GPS in EXIF (leave false — privacy risk) |
| `ocr`            | `boolean`               | `true`     | Run on-device OCR and return `ocrText`           |

## Result types

```ts
type ScanReceiptResult = {
  status: 'success' | 'cancelled';
  images: ReceiptImage[];
};

type ReceiptImage = {
  uri: string; // file:// path — never base64
  width: number;
  height: number;
  fileName: string;
  mimeType: 'image/jpeg';
  fileSize: number;
  ocrText?: string; // present when options.ocr === true
  exif?: ReceiptExif; // present when options.includeExif === true
};

type ReceiptExif = {
  orientation?: number;
  dateTimeOriginal?: string;
  make?: string;
  model?: string;
  gps?: { latitude: number; longitude: number };
};
```

## Architecture docs

- [API contract](docs/specs/api-contract.md) — full type reference and package boundaries
- [Scan pipeline](docs/specs/scan-pipeline.md) — internal processing flow (contributors)
- [Phase 1 — JS wrapper](docs/plans/phase-1-js-wrapper.md)
- [Phase 2 — Android](docs/plans/phase-2-android.md)
- [Phase 3 — iOS](docs/plans/phase-3-ios.md)

## Contributing

- [Development workflow](CONTRIBUTING.md#development-workflow)
- [Sending a pull request](CONTRIBUTING.md#sending-a-pull-request)
- [Code of conduct](CODE_OF_CONDUCT.md)

## License

MIT
