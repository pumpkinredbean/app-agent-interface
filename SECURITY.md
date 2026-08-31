# Security policy

## Reporting a vulnerability

Do not disclose security vulnerabilities in a public issue.

Use GitHub's private vulnerability reporting flow at:

<https://github.com/pumpkinredbean/app-agent-interface/security/advisories/new>

Include the affected version or commit, impact, reproduction steps, and any suggested mitigation. Reports involving credential exposure should identify the credential type without including an active secret.

## Supported versions

The project is experimental before `1.0.0`. Security fixes target the latest release and the `main` branch; older pre-release versions may require upgrading.

## Security boundary

- Mobile application credentials stay inside application-owned handlers.
- Tool arguments are untrusted and are validated again at the mobile execution boundary.
- Sensitive operations can require application-owned approval.
- The bundled transport binds to loopback only; remote connectivity requires a separately authenticated relay.
- The optional UiAutomator2 driver is intended for controlled development and evaluation and is scoped to one target package.
