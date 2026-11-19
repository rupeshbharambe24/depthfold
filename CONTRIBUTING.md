# Contributing to Depthfold

Depthfold is in early, focused development. The core capture, 3D model and measurement pipeline is being built
and validated on real phones first, so outside code contributions are not being sought yet.

- **Bug reports, device results and questions are welcome** in
  [Issues](https://github.com/rupeshbharambe24/depthfold/issues) and
  [Discussions](https://github.com/rupeshbharambe24/depthfold/discussions).
- **Before writing code, open an issue** and wait for a go-ahead, so that work fits the current plan. Unsolicited
  pull requests may be closed.

The rest of this guide is how the project is built and tested, for anyone working on it.

## Development setup

### Processing toolkit (Python)

Requires [uv](https://docs.astral.sh/uv/). uv creates the project's own environment in `processing/.venv`.

```bash
cd processing
uv sync
uv run pytest            # unit and end-to-end tests, about a minute
uv run ruff check .      # lint
uv run depthfold --help
```

The end-to-end tests generate synthetic captures with known dimensions and assert the measured error, and print a
table of measured errors at the end of the run. If you change fusion or measurement, include that table in your
pull request.

### Android app

Requires JDK 17 and an Android SDK with platform 37 (Android Studio provides both).

```bash
cd android
./gradlew testDebugUnitTest   # JVM unit tests, no device needed
./gradlew lint
./gradlew assembleDebug       # app/build/outputs/apk/debug/app-debug.apk
```

Keep logic that can be tested without Android (bundle writing and reading, fusion, meshing, file formats, image
conversion) in plain Kotlin under `capture/` and `model/`, with JVM unit tests. If you test on a phone, say which model in the pull request.

### Both together

```bash
scripts/interop-check.sh
```

This checks both directions between the app and the toolkit: a bundle written by the Android writer code (as a JVM
test) must validate and fuse correctly in the toolkit, and a mesh built by the app's on-device model builder from a
toolkit-made synthetic bottle must measure correctly in the toolkit. Run it whenever you touch the bundle writer,
the reader or the model builder.

### README figure

`docs/assets/pipeline.png` is produced by the toolkit itself. After changing fusion or measurement, regenerate it:

```bash
cd processing && uv run --group docs python scripts/render_readme_assets.py ../docs/assets
```

## Ground rules

- **The bundle spec is the contract.** Any change to what the recorder writes or the toolkit reads must update
  [`docs/capture-bundle.md`](docs/capture-bundle.md) in the same pull request, and keep older bundles readable
  where possible.
- **Honest numbers.** Accuracy claims need a test or a capture that backs them, and measured values carry units
  and an uncertainty.
- **Permissive dependencies only.** Apache-2.0, MIT, BSD or similar. No GPL/AGPL code or non-commercial model
  weights in the core; optional backends with other licences must stay optional and be clearly labelled.
- **Privacy.** Captures are camera images. Never commit or attach captures that show people or private spaces.

## Pull requests

- Keep each pull request focused on one change, and add tests for new behaviour.
- Fill in the pull request template; CI must pass.
- Write commit messages in the imperative ("Add cylinder taper fit"), with a short body explaining why when it is
  not obvious.
- Add a line to the `[Unreleased]` section of [CHANGELOG.md](CHANGELOG.md) for user-visible changes.

## Licence

By contributing you agree that your contributions are licensed under the [Apache License 2.0](LICENSE).
Everyone taking part follows the [Code of Conduct](CODE_OF_CONDUCT.md).
