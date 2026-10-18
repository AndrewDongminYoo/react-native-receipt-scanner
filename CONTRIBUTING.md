# Contributing

Contributions are always welcome, no matter how large or small!

We want this community to be friendly and respectful to each other. Please follow it in all your interactions with the project. Before contributing, please read the [code of conduct](./CODE_OF_CONDUCT.md).

## Development workflow

This project is a monorepo managed using [Yarn workspaces](https://yarnpkg.com/features/workspaces). It contains the following packages:

- The library package in the root directory.
- An example app in the `example/` directory.

To get started with the project, make sure you have the correct version of [Node.js](https://nodejs.org/) installed. See the [`.nvmrc`](./.nvmrc) file for the version used in this project.

Run `yarn` in the root directory to install the required dependencies for each package:

```sh
yarn
```

> Since the project relies on Yarn workspaces, you cannot use [`npm`](https://github.com/npm/cli) for development without manually migrating.

The [example app](/example/) demonstrates usage of the library. You need to run it to test any changes you make.

It is configured to use the local version of the library, so any changes you make to the library's source code will be reflected in the example app. Changes to the library's JavaScript code will be reflected in the example app without a rebuild, but native code changes will require a rebuild of the example app.

If you want to use Android Studio or Xcode to edit the native code, you can open the `example/android` or `example/ios` directories respectively in those editors.
To edit the library's Objective-C files, open `example/ios/ReceiptScannerExample.xcworkspace` in Xcode and find the source files at `Pods > Development Pods > ReceiptScanner`.
The example's Swift app and scene delegates are in `example/ios/ReceiptScannerExample/AppDelegate.swift`.

To edit the Java or Kotlin files, open `example/android` in Android studio and find the source files at `react-native-receipt-scanner` under `Android`.

You can use various commands from the root directory to work with the project.

To start the packager:

```sh
yarn example start
```

To run the example app on Android:

```sh
yarn example android
```

To run the example app on iOS:

```sh
yarn example ios
```

To confirm that the app is running with the new architecture, you can check the Metro logs for a message like this:

```sh
Running "ReceiptScannerExample" with {"fabric":true,"initialProps":{"concurrentRoot":true},"rootTag":1}
```

Note the `"fabric":true` and `"concurrentRoot":true` properties.

To run the example app on Web:

```sh
yarn example web
```

Make sure your code passes TypeScript:

```sh
yarn typecheck
```

To check for linting errors, run the following:

```sh
yarn lint
```

To fix formatting errors, run the following:

```sh
yarn lint --fix
```

Remember to add tests for your change if possible. Run the unit tests by:

```sh
yarn test
```

### iOS build and launch troubleshooting

Run the commands in this section from the repository root.
An `xcodebuild` exit code of `65` is a summary, so inspect the first underlying error in Xcode's build log before resetting caches or reinstalling dependencies.
A successful build and launch command does not confirm that the app stays running; startup failures require the runtime console or a device crash report.

#### Missing Node executable after a Node upgrade

React Native reads `example/ios/.xcode.env` and then overrides it with `.xcode.env.local`.
An old `NODE_BINARY` path in the local file can make RNDeps, Hermes, or bundle script phases fail with `No such file or directory`, even when Node works in the terminal.
Activate the version in `.nvmrc` first; with nvm, run `nvm use` from the repository root.
Then regenerate the local path:

```sh
node --version
printf 'export NODE_BINARY="%s"\n' "$(node -p 'process.execPath')" > example/ios/.xcode.env.local
```

Repeat this step after replacing the Node installation referenced by that file.
The local environment file is not committed.

#### Stale Hermes paths after moving a checkout

Pods can retain an absolute `HERMES_CLI_PATH` in generated `.xcconfig` files and the cached `hermes-engine` podspec.
If the bundle phase references an old checkout path but `hermesc` exists under the current `node_modules`, regenerate Pods from the current checkout:

```sh
(cd example && bundle install && bundle exec pod install --project-directory=ios)
rg 'HERMES_CLI_PATH' 'example/ios/Pods/Target Support Files/Pods-ReceiptScannerExample'
```

Confirm that the paths point to this checkout before rebuilding.
Do not edit the generated `.xcconfig` files by hand.

#### Pods installation timed out or did not finish

An interrupted install can leave downloaded artifacts without `Pods.xcodeproj`, `Manifest.lock`, or the app's support files.
Xcode may then report missing file lists under `/Target Support Files/...` because the configuration that defines `PODS_ROOT` was not generated.
Rerun the install and wait for it to succeed before building:

```sh
(cd example && bundle exec pod install --project-directory=ios)
test -f example/ios/Pods/Manifest.lock
test -f example/ios/Pods/Pods.xcodeproj/project.pbxproj
```

If Bundler reports a missing version or gems, check `ruby --version` and `bundle --version` in the same shell and activate your intended Ruby installation.
Do not manually assign `PODS_ROOT` to hide an incomplete install.

#### App closes immediately with a UIScene assertion

Apps built with the iOS 27 SDK must adopt the UIKit scene lifecycle or they fail to launch, as described in [Apple's migration guide](https://developer.apple.com/documentation/uikit/transitioning-to-the-uikit-scene-based-life-cycle).
The example registers a single Scene in `Info.plist`; `SceneDelegate` creates `UIWindow(windowScene:)` and starts React Native.
Keep window setup in the Scene callback and retain the React Native delegate and factory for the Scene's lifetime.
The deployment target does not replace this SDK requirement.

If the app still exits after `Build Succeeded`, open the workspace in Xcode and inspect its runtime console or copy the app's crash report with `devicectl`.
Replace `DEVICE_ID` and `CRASH_FILE.ips` below with values from the device and file listings:

```sh
xcrun devicectl list devices
xcrun devicectl device info files --device DEVICE_ID --domain-type systemCrashLogs
xcrun devicectl device copy from --device DEVICE_ID --domain-type systemCrashLogs --source CRASH_FILE.ips --destination /tmp/receipt-scanner-crash.ips
```

These commands read the crash report without reinstalling or relaunching the app.

### Trunk (multi-language linters & formatters)

The Yarn scripts above only cover the JS/TS surface.
The repo additionally uses [Trunk](https://docs.trunk.io/cli) (configured in `.trunk/trunk.yaml`) to lint and format Kotlin (ktlint), shell, YAML, Markdown, and to run security scans (OSV-Scanner, TruffleHog).
The enabled pre-push hook invokes Trunk checks, so getting them green locally is a prerequisite for pushing.

```sh
trunk fmt    # auto-format every file the trunk formatters own (run first)
trunk check  # run applicable enabled linters on changed files
```

### Verification checklist before opening a PR

Run this exact pipeline and confirm it is clean before requesting review. `trunk fmt` rewrites files in place — run it **before** `trunk check` so the formatters don't report unformatted files as failures.

```sh
yarn typecheck && yarn lint && yarn test && trunk fmt && trunk check
```

If `trunk check` reports a finding you believe is a false positive, suppress it in the appropriate `.trunk/configs/<linter>.toml` with a written justification (OSV advisories go in the root `osv-scanner.toml`, which is also the canonical pattern) — do not silence findings inline.

### Publishing to npm

Releases are driven by [release-it](https://github.com/release-it/release-it) locally, and the npm publish itself happens in CI.
`yarn release` bumps `package.json`, regenerates `CHANGELOG.md`, commits with `chore: 🔖 release vX.Y.Z`, pushes the `vX.Y.Z` tag, and publishes a GitHub Release.
The GitHub Release then triggers the `publish-npm` job in `.github/workflows/release.yml`, which publishes to the public npm registry via [OIDC trusted publishing](https://docs.npmjs.com/trusted-publishers/) — no npm token secret is stored in the repo.
The `after:bump` release hook builds with Yarn and checks that the `ReceiptScanner` version in the `PODS` section of `example/ios/Podfile.lock` equals `package.json.version` before the release commit and tag.
The same check is registered as `prepublishOnly`, which [npm runs before publishing](https://docs.npmjs.com/cli/v11/using-npm/scripts/#npm-publish); it rejects missing, duplicate, or mismatched entries and missing lockfiles.
Run the check manually from the repository root with `yarn prepublishOnly`.

If `yarn release` stops after bumping the package because the lockfile is stale, regenerate Pods and resume the same version:

```sh
(cd example && bundle exec pod install --project-directory=ios)
yarn prepublishOnly
yarn release --no-increment
```

Review the regenerated `Podfile.lock` with the package version change and include both in the release commit.
Do not edit the lockfile by hand or disable lifecycle scripts to bypass the guard.
Development and release preparation use Yarn; the CI registry upload remains `npm publish` for trusted publishing.
Both the GitHub repository and the npm package are public, so trusted publishing also generates [provenance attestations](https://docs.npmjs.com/generating-provenance-statements).
Provenance generation is a hard requirement of the publish step — if it fails, `npm publish` fails with it.
Do not set `NPM_CONFIG_PROVENANCE: "false"` to work around that unless the repository goes private again, in which case attestations genuinely cannot be produced.

### Scripts

The `package.json` file contains various scripts for common tasks:

- `yarn`: setup project by installing dependencies.
- `yarn typecheck`: type-check files with TypeScript.
- `yarn lint`: lint files with [ESLint](https://eslint.org/).
- `yarn test`: run unit tests with [Jest](https://jestjs.io/).
- `yarn prepublishOnly`: verify that the example's locked ReceiptScanner version matches the package version.
- `trunk check`: run applicable enabled [Trunk](https://docs.trunk.io/cli) linters on changed files (ktlint, shellcheck, yamllint, OSV-Scanner, …).
- `trunk fmt`: auto-format every file the Trunk formatters own.
- `yarn example start`: start the Metro server for the example app.
- `yarn example android`: run the example app on Android.
- `yarn example ios`: run the example app on iOS.
- `yarn example web`: run the example app on Web.
- `yarn example build:web`: build the example app for Web.

### Sending a pull request

> **Working on your first pull request?** You can learn how from this _free_ series: [How to Contribute to an Open Source Project on GitHub](https://app.egghead.io/playlists/how-to-contribute-to-an-open-source-project-on-github).

When you're sending a pull request:

- Prefer small pull requests focused on one change.
- Verify that linters and tests are passing.
- Review the documentation to make sure it looks good.
- Follow the pull request template when opening a pull request.
- For pull requests that change the API or implementation, discuss with maintainers first by opening an issue.
