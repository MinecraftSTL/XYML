# XoyzMCP

XoyzMCP is a compact Java 17 library for exposing application-provided tools, resources, and prompts through an MCP
Streamable HTTP server. It implements JSON-RPC request handling, protocol-version negotiation, loopback HTTP safety,
session identifiers, JSON responses, and optional SSE-formatted responses without imposing application-specific
operations.

The API accepts independent providers for the three MCP capability families. A capability is advertised only when its
provider is present. Applications retain ownership of scheduling, authorization, filesystem access, and business
operations.

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
