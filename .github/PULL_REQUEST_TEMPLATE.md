## What this changes

<!-- One or two sentences. Link the issue it closes, e.g. "Closes #12". -->

## How it was tested

<!-- Commands you ran and their result; for app changes, the phone model you tried it on. -->

## Checklist

- [ ] `cd processing && uv run pytest` passes (toolkit changes)
- [ ] `cd android && ./gradlew testDebugUnitTest lint` passes (app changes)
- [ ] `scripts/interop-check.sh` passes (bundle reader or writer changes)
- [ ] `docs/capture-bundle.md` is updated if the bundle format changed
- [ ] Accuracy claims are backed by a test or a capture
- [ ] New dependencies are permissively licensed (Apache-2.0, MIT, BSD or similar)
