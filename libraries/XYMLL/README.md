# XYMLL for Windows

XYML's native Windows launcher, based on HMCLauncher 3.7.0.1. The fork is renamed to satisfy the upstream license's
modified-version condition and retains the original author's copyright in the executable metadata.

## Build

```powershell
.\gradlew.bat :XYMLL:build
```

The root Gradle build invokes CMake with the Visual Studio 2022 generator, the Win32 architecture, and Microsoft
Visual C++. Non-Windows hosts verify and use the checked-in fallback executable generated from this same source.

## License

The project is distributed under [GPLv3](https://www.gnu.org/licenses/gpl-3.0.html) license with the following
additional terms inherited from HMCLauncher:

### Additional terms under GPLv3 Section 7

1. When you distribute a modified version of the software, you must change the software name or the version number in a reasonable way in order to distinguish it from the original version. (Under [GPLv3, 7(c)](https://github.com/HMCL-dev/HMCL/blob/11820e31a85d8989e41d97476712b07e7094b190/LICENSE#L372-L374))

   The software name and the version number can be edited [here](https://github.com/HMCL-dev/HMCL/blob/javafx/HMCL/src/main/java/org/jackhuang/hmcl/Metadata.java#L33-L35).

2. You must not remove the copyright declaration displayed in the software. (Under [GPLv3, 7(b)](https://github.com/HMCL-dev/HMCL/blob/11820e31a85d8989e41d97476712b07e7094b190/LICENSE#L368-L370))
