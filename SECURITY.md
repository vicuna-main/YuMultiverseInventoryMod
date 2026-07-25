# Security Policy

## Reporting a vulnerability

Do not publish player-data corruption, duplication, path handling, dependency compromise, or
permission-bypass details in a public issue before a fix is available. Send a private report to the
project maintainer with the affected version, reproduction steps, expected impact, and any relevant
logs with player-identifying data removed.

Reports should receive an acknowledgement within seven days. Fixes for confirmed high-impact data
integrity or security defects should be developed privately, covered by regression tests, and
released with migration and recovery instructions.

## Supported versions

Only the latest published release is supported. Servers should retain backups of the complete
`<world>/data/mwi/` directory before upgrades and keep dependency verification enabled when building
from source.
