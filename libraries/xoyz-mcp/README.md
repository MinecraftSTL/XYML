# XoyzMCP

XoyzMCP is a compact Java 17 library for exposing application-provided tools, resources, and prompts through an MCP
Streamable HTTP server. It implements JSON-RPC request handling, protocol-version negotiation, loopback HTTP safety,
session identifiers, JSON responses, and optional SSE-formatted responses without imposing application-specific
operations. Applications can optionally configure a Bearer token; when configured, every request to `/mcp` must
carry the matching `Authorization: Bearer <token>` header.

The API accepts independent providers for the three MCP capability families. A capability is advertised only when its
provider is present. Applications retain ownership of scheduling, authorization, filesystem access, and business
operations. An empty bearer token preserves the unauthenticated loopback mode; applications that expose the listener
beyond a tightly controlled process should configure a non-empty token and still retain their own authorization policy.
The transport-layer Bearer gate and the application's authorization policy are separate layers: passing the former
does not grant a client permission to invoke every exposed operation.

## Authentication

The four transport entry points (`POST` initialization and messages, `GET`, `DELETE`, and transport errors) share one
Bearer credential gate before session or provider dispatch. Free-form provider values and diagnostic text are redacted
without echoing the configured credential. Protocol-defined literals remain unchanged, and provider identifiers are
only replaced when they exactly equal the credential so short credentials do not corrupt tool names or resource URIs.
Use a high-entropy token; a short token can naturally occur as characters in fixed protocol text. Authorization header
names are case-insensitive, but duplicate `Authorization` names are rejected as an invalid request. The server
compares SHA-256 digests with a constant-time comparison; it does not log credentials.

## Coordinates

Gradle:

```kotlin
dependencies {
    implementation("space.minecraftstl.xyml:xoyz-mcp:<version>")
}
```

Maven:

```xml
<dependency>
  <groupId>space.minecraftstl.xyml</groupId>
  <artifactId>xoyz-mcp</artifactId>
  <version>&lt;version&gt;</version>
</dependency>
```

## License

XoyzMCP is licensed under the GNU General Public License version 3 or later.
