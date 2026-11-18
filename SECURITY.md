# Security policy

## Supported versions

Depthfold is in alpha. Security fixes go into the latest release on `main` only.

## Reporting a vulnerability

Please **do not open a public issue** for security problems. Report them privately through GitHub:
[Security → Report a vulnerability](https://github.com/rupeshbharambe24/depthfold/security/advisories/new).

Include what is affected (Android app, processing toolkit or bundle format), how to reproduce it, and the impact
you expect. You will get an acknowledgement within 7 days.

Things that are in scope include malformed capture bundles or zip files that make the toolkit write outside its
output folder or execute code, the Android app exposing captures to other apps without the user sharing them, and
leaks of camera images or location data.

## Captures are personal data

Capture bundles contain camera images and can show people, homes and documents. Never attach a capture to a public
issue unless you are sure it contains nothing private.
