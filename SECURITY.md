# Security and privacy policy

Iustitia is designed to be client-side and local-only. It observes incoming state that the Minecraft client already receives and stores optional evidence on the user's machine. It should not upload detections, read unrelated files, or send gameplay actions while observing.

## Report privately

Please do not publish a security or privacy vulnerability in a public issue. Use GitHub's private vulnerability reporting if it is enabled for the repository. If that channel is unavailable, contact the maintainers through the private contact listed on the repository profile.

Include:

- A clear description of the issue.
- The affected version or commit.
- Reproduction steps or a minimal proof of concept.
- The expected behavior.
- The actual behavior and impact.
- Logs or screenshots with secrets and personal information removed.
- Whether the issue is reproducible with only Iustitia and required dependencies.

Do not include live tokens, account credentials, private server credentials, unredacted IPs, or another person's private data.

## In-scope concerns

Examples include:

- Unexpected outgoing packets or server-facing actions.
- Unexpected telemetry, uploads, analytics, or remote connections.
- Reading or writing files outside documented local data paths.
- Bypassing the local-only persistence boundary.
- Credential, token, or sensitive-log exposure.
- A crash or stuck camera/input state that cannot be safely restored.
- A mixin or replay path that mutates live gameplay outside its documented scope.
- Unsafe parsing of imported clips, configs, presets, or chat data.

## Design expectations

Security fixes should preserve:

- Local-only behavior.
- Fail-open behavior where safe.
- Bounded storage and input sizes.
- Sanitized filenames.
- No hidden network communication.
- Clear user-facing documentation when behavior changes.

A security fix may be backported or released before the full implementation is discussed publicly. Maintainers will decide disclosure timing based on user impact and the availability of a fix.