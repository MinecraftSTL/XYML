# HMCLauncher for Windows, Copyright (C) 2025 huangyuhui and contributors.
# Modified by MinecraftSTL in 2026 for XYMLL. See README.md for the GPLv3 Section 7 additional terms.
cmake_minimum_required(VERSION 3.16)

set(XYMLL_PRODUCT_VERSION "1.0.0.0" CACHE STRING "Four-component Windows product version")
set(XYMLL_DISPLAY_VERSION "1.0.0" CACHE STRING "XYML release version shown to users")
set(XYMLL_UPSTREAM_VERSION "3.7.0.1" CACHE STRING "Imported HMCLauncher version")

project(XYMLL
        LANGUAGES CXX
        VERSION ${XYMLL_PRODUCT_VERSION}
)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

set(CMAKE_WIN32_EXECUTABLE ON)

set(XYML_EXPECTED_JAVA_MAJOR_VERSION 17)

add_compile_definitions(UNICODE _UNICODE)
add_compile_definitions(WINVER=0x0601 _WIN32_WINNT=0x0601) # Windows 7

if (NOT MSVC)
    message(FATAL_ERROR "XYMLL must be built with Microsoft Visual C++")
endif ()

set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")

include_directories(${CMAKE_CURRENT_BINARY_DIR})

configure_file(XYMLL.ico ${CMAKE_CURRENT_BINARY_DIR}/XYMLL.ico COPYONLY)
configure_file(XYMLL/XYMLL.rc.in ${CMAKE_CURRENT_BINARY_DIR}/XYMLL.rc @ONLY)
configure_file(XYMLL/config.h.in ${CMAKE_CURRENT_BINARY_DIR}/config.h @ONLY)

add_executable(XYMLL WIN32
        ${CMAKE_CURRENT_BINARY_DIR}/XYMLL.rc
        XYMLL/main.cpp
        XYMLL/platform.cpp XYMLL/platform.h
        XYMLL/i18n.cpp XYMLL/i18n.h
        XYMLL/java.cpp XYMLL/java.h
        XYMLL/path.cpp XYMLL/path.h
        XYMLL/debug.cpp XYMLL/debug.h
)
target_compile_options(XYMLL PRIVATE /utf-8 /W4)
target_link_options(XYMLL PRIVATE /ENTRY:wWinMainCRTStartup)
target_link_libraries(XYMLL PRIVATE shlwapi version)
