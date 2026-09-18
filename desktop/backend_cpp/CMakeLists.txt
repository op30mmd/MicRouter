cmake_minimum_required(VERSION 3.16)
project(microuter_backend CXX)

# ---------------------------------------------------------------------------
# MicRouter C++ backend — a drop-in replacement for desktop/backend/backend.py
#
# Depends only on PortAudio. RNNoise is loaded at runtime when available
# (see src/plugins/rnnoise.cc), so a plain build succeeds without it.
#
# PortAudio is consumed via FetchContent: if the system already provides it
# (pkg-config on Linux), that is preferred; otherwise the pinned upstream
# release is fetched and built with its own CMake rules, which handle the
# Windows (WASAPI/DS/MME/WDMKS) and Linux (ALSA/Pulse/JACK) backends.
# ---------------------------------------------------------------------------

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if(NOT CMAKE_BUILD_TYPE AND NOT CMAKE_CONFIGURATION_TYPES)
  set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)
endif()

add_executable(microuter_backend
  src/main.cc
  src/core/backend.cc
  src/core/audio_device.cc
  src/core/wire.cc
  src/plugins/rnnoise.cc
)
target_include_directories(microuter_backend PRIVATE src)

if(MSVC)
  target_compile_options(microuter_backend PRIVATE /W4 /EHsc)
else()
  target_compile_options(microuter_backend PRIVATE -Wall -Wextra)
endif()

# ---- PortAudio --------------------------------------------------------------
find_package(PkgConfig QUIET)
if(PkgConfig_FOUND)
  pkg_check_modules(PORTAUDIO QUIET portaudio-2.0)
endif()

if(PORTAUDIO_FOUND)
  # Use the system library discovered via pkg-config. PORTAUDIO_LDFLAGS
  # already contains "-L... -lportaudio"; _OTHER carries transitive flags
  # (-lm, -lpthread) that static linking would otherwise need.
  target_include_directories(microuter_backend PRIVATE ${PORTAUDIO_INCLUDE_DIRS})
  target_link_libraries(microuter_backend PRIVATE
    ${PORTAUDIO_LDFLAGS} ${PORTAUDIO_LDFLAGS_OTHER})
else()
  include(FetchContent)
  set(PORTAUDIO_FETCH_VERSION v19.7.0)
  # PortAudio's own CMakeLists declares cmake_minimum_required(VERSION 3.2);
  # allow recent CMake (>= 4.x removed < 3.5 compatibility) to configure it.
  set(CMAKE_POLICY_VERSION_MINIMUM 3.5 CACHE STRING "" FORCE)
  FetchContent_Declare(
    portaudio
    URL "https://github.com/PortAudio/portaudio/archive/refs/tags/${PORTAUDIO_FETCH_VERSION}.tar.gz"
    DOWNLOAD_EXTRACT_TIMESTAMP TRUE
  )
  # Static lib only: keeps the distributable self-contained and avoids a DLL
  # naming dance (PortAudio's CMake names the shared lib `portaudio` vs
  # `portaudio_x64` depending on toolchain).
  set(PA_BUILD_SHARED OFF CACHE BOOL "" FORCE)
  set(PA_BUILD_STATIC ON CACHE BOOL "" FORCE)
  # Disable backends we do not need to cut down on external dependencies,
  # keeping the default Audio backend set otherwise.
  set(PA_USE_JACK OFF CACHE BOOL "" FORCE)
  FetchContent_MakeAvailable(portaudio)
  target_link_libraries(microuter_backend PRIVATE portaudio_static)
endif()

if(WIN32)
  target_compile_definitions(microuter_backend PRIVATE WIN32_LEAN_AND_MEAN NOMINMAX)
  # portaudio_static already propagates winmm/dsound/ole32 via its own
  # PA_LIBRARY_DEPENDENCIES; ws2_32 is for the backend's sockets.
  target_link_libraries(microuter_backend PRIVATE ws2_32)
else()
  target_link_libraries(microuter_backend PRIVATE dl)
endif()
