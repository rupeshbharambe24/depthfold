# Contributing to Depthfold

Thanks for helping. Depthfold is young, so there is room to shape it, and most contributions do not need a phone.

## Ways to contribute

| You have | You can |
|---|---|
| An [ARCore phone](https://developers.google.com/ar/devices) | Record a ruler-measured object and file a [device accuracy report](https://github.com/rupeshbharambe24/depthfold/issues/new?template=device_report.yml) |
| Python / geometry skills | Improve fusion, add shape fits, speed up measurement, add export formats |
| Android / Kotlin skills | Capture guidance, on-device preview, UI, robustness on real devices |
| Knowledge of a domain | Design a [domain pack](https://github.com/rupeshbharambe24/depthfold/issues/new?template=domain_pack.yml) (parcels, livestock, furniture, plants, ...) |
| Writing skills | Tutorials, clearer docs, translations |

Issues labelled [`good first issue`](https://github.com/rupeshbharambe24/depthfold/labels/good%20first%20issue) are
scoped for a first contribution, and [`help wanted`](https://github.com/rupeshbharambe24/depthfold/labels/help%20wanted)
marks work where outside help is especially welcome. Comment on an issue before starting so work is not duplicated.
For larger changes, open an issue or a [discussion](https://github.com/rupeshbharambe24/depthfold/discussions) first.

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

Keep logic that can be tested without Android (bundle writing, geometry, image conversion) in plain Kotlin under
`capture/`, with JVM unit tests. If you test on a phone, say which model in the pull request.

### Both together

```bash
scripts/interop-check.sh
```

This writes a bundle with the Android writer code (as a JVM test) and checks that the toolkit validates it and
reconstructs the scene correctly. Run it whenever you touch the bundle writer or reader.

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
