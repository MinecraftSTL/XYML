# Java Mesa Loader for Windows

A Java agent that enables Java applications to load Mesa drivers to replace the default OpenGL implementation.

Usgae:

```bash
java -javaagent:mesa-loader-windows.jar=<driver name> -jar your-application.jar ...
```

Supported drivers:

* `llvmpipe`
* `d3d12`
* `zink`

Requires Windows 10 (or later), Java 6 (or later).

Based on Mesa builds provided by [mmozeiko/build-mesa](https://github.com/mmozeiko/build-mesa).

## XYML monorepo fork

This directory is the namespaced XYML fork of upstream version 26.0.4. It is built and published independently as
`space.minecraftstl.xyml:mesa-loader-windows`, with `x86`, `x64`, and `arm64` classifiers whose artifact version
follows the XYML release version. The loader retains Java 6 bytecode compatibility.

XYML deliberately continues downloading the original `org.glavo:mesa-loader-windows:26.0.4` runtime artifacts from
its `natives.json`; the large local classifiers participate in the monorepo build and verification but are not
embedded into the XYML application artifact.

## License

This project is licensed under the [Apache 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) license.
