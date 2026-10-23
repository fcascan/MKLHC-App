# radiod - MKLHC-App radio helper (GPL-2.0-only). Links devourer (https://github.com/OpenIPC/devourer, GPL-2.0-only); no devourer source copied into this file.
#
# Desktop build of the radiod helper. Configure and build:
#
#   cmake -S radiod -B radiod/build
#   cmake --build radiod/build --config Release
#
# Needs: CMake >= 3.15, a C++20 compiler, libusb-1.0 and pkg-config (devourer's own CMakeLists finds libusb
# through pkg-config), and the devourer submodule at radiod/devourer (see radiod/README.md).
# On Windows set VCPKG_ROOT to a vcpkg checkout that has libusb installed; this file then loads the vcpkg
# toolchain before project(), which devourer's own CMakeLists would do too late when added as a subdirectory.

cmake_minimum_required(VERSION 3.15)

if(WIN32 AND NOT CMAKE_TOOLCHAIN_FILE AND DEFINED ENV{VCPKG_ROOT} AND NOT "$ENV{VCPKG_ROOT}" STREQUAL "")
    set(CMAKE_TOOLCHAIN_FILE "$ENV{VCPKG_ROOT}/scripts/buildsystems/vcpkg.cmake" CACHE STRING "vcpkg toolchain file")
endif()

# devourer contains C sources (the Kestrel halbb/halrf code), so both languages are enabled.
project(radiod LANGUAGES C CXX)

if(NOT EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/devourer/CMakeLists.txt")
    message(FATAL_ERROR
        "radiod: devourer is missing at ${CMAKE_CURRENT_SOURCE_DIR}/devourer.\n"
        "Add it as a git submodule from the repository root:\n"
        "  git submodule add https://github.com/OpenIPC/devourer radiod/devourer\n"
        "  git submodule update --init radiod/devourer\n"
        "See radiod/README.md.")
endif()

# Early, friendlier diagnostic for the most common setup problem; devourer's own REQUIRED check follows.
find_package(PkgConfig QUIET)
if(PkgConfig_FOUND)
    pkg_check_modules(RADIOD_LIBUSB_PROBE QUIET libusb-1.0)
endif()
if(NOT PkgConfig_FOUND OR NOT RADIOD_LIBUSB_PROBE_FOUND)
    message(WARNING
        "radiod: libusb-1.0 was not found through pkg-config. devourer requires it.\n"
        "  Windows: install vcpkg, run 'vcpkg install libusb pkgconf', set VCPKG_ROOT, then reconfigure.\n"
        "  Debian/Ubuntu: apt install pkg-config libusb-1.0-0-dev. macOS: brew install pkg-config libusb.")
endif()

# Short commit of the devourer checkout, reported to the app in READY. Only trusted when devourer is its own
# git checkout (a submodule has a .git file); otherwise git would report the parent repository's commit.
set(RADIOD_DEVOURER_VERSION "unknown")
find_package(Git QUIET)
if(Git_FOUND AND EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/devourer/.git")
    execute_process(
        COMMAND "${GIT_EXECUTABLE}" -C "${CMAKE_CURRENT_SOURCE_DIR}/devourer" rev-parse --short=12 HEAD
        OUTPUT_VARIABLE RADIOD_DEVOURER_SHA
        OUTPUT_STRIP_TRAILING_WHITESPACE
        ERROR_QUIET
        RESULT_VARIABLE RADIOD_DEVOURER_SHA_RESULT)
    if(RADIOD_DEVOURER_SHA_RESULT EQUAL 0 AND RADIOD_DEVOURER_SHA)
        set(RADIOD_DEVOURER_VERSION "${RADIOD_DEVOURER_SHA}")
    endif()
endif()

# devourer builds a library plus about twenty example and test executables with no switch to turn them off.
# EXCLUDE_FROM_ALL builds only what radiod links: the devourer library and its object libraries.
# Default chip options (every chip on) are kept on purpose.
set(BUILD_SHARED_LIBS OFF)
add_subdirectory(devourer EXCLUDE_FROM_ALL)

find_package(Threads REQUIRED)

add_executable(radiod
    src/main.cpp
    src/Ipc.cpp
    src/Radio.cpp
    src/UsbScan.cpp)

target_compile_features(radiod PRIVATE cxx_std_20)
target_compile_definitions(radiod PRIVATE RADIOD_DEVOURER_VERSION="${RADIOD_DEVOURER_VERSION}")

# devourer is PUBLIC-linked to libusb and adds its src/ and hal/ include directories, so linking the
# target is enough to get <libusb.h> and the devourer headers.
target_link_libraries(radiod PRIVATE devourer Threads::Threads)

if(WIN32)
    target_link_libraries(radiod PRIVATE ws2_32)
    target_compile_definitions(radiod PRIVATE NOMINMAX WIN32_LEAN_AND_MEAN _CRT_SECURE_NO_WARNINGS)
endif()

if(MSVC)
    target_compile_options(radiod PRIVATE /W3 /utf-8)
else()
    target_compile_options(radiod PRIVATE -Wall -Wextra)
endif()
