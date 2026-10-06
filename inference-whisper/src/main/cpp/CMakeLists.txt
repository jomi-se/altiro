cmake_minimum_required(VERSION 3.22)
project(altiro_whisper LANGUAGES C CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
# Upstream still declares an old minimum. Preserve current policy compatibility.
set(CMAKE_POLICY_VERSION_MINIMUM 3.5 CACHE STRING "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_IS_DEV OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_CPU ON CACHE BOOL "" FORCE)
set(GGML_METAL OFF CACHE BOOL "" FORCE)
set(GGML_CUDA OFF CACHE BOOL "" FORCE)
option(ALTIRO_VULKAN "Build the experimental Android Vulkan backend" ${ANDROID})
set(GGML_VULKAN ${ALTIRO_VULKAN} CACHE BOOL "" FORCE)
set(GGML_CPU_KLEIDIAI OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
include(FetchContent)
if(ALTIRO_VULKAN)
    FetchContent_Declare(vulkan_headers
        URL https://codeload.github.com/KhronosGroup/Vulkan-Headers/tar.gz/2cd90f9d20df57eac214c148f3aed885372ddcfe
        URL_HASH SHA256=e3865307e665f6a4ac4f7bbca7b3ba95e4191c7f8adf9f04b084ef514a2454e9
        DOWNLOAD_EXTRACT_TIMESTAMP TRUE)
    FetchContent_Declare(vulkan_hpp
        URL https://codeload.github.com/KhronosGroup/Vulkan-Hpp/tar.gz/78243585183d42c93eeb2c57f9a194c7cac40bcc
        URL_HASH SHA256=1c2d15b111c68d40a07607a156251d6a364c77a1adda4d80366507907e17d4a7
        SOURCE_SUBDIR altiro-no-auto-subdir
        DOWNLOAD_EXTRACT_TIMESTAMP TRUE)
    FetchContent_Declare(SPIRV-Headers
        URL https://codeload.github.com/KhronosGroup/SPIRV-Headers/tar.gz/2a611a970fdbc41ac2e3e328802aed9985352dca
        URL_HASH SHA256=c2225a49c3d7efa5c4f4ce4a6b42081e6ea3daca376f3353d9d7c2722d77a28a
        OVERRIDE_FIND_PACKAGE
        DOWNLOAD_EXTRACT_TIMESTAMP TRUE)
    FetchContent_MakeAvailable(vulkan_headers vulkan_hpp SPIRV-Headers)
    set(Vulkan_INCLUDE_DIR "${vulkan_headers_SOURCE_DIR}/include" CACHE PATH "" FORCE)
    find_package(Vulkan COMPONENTS glslc REQUIRED)
    execute_process(COMMAND "${Vulkan_GLSLC_EXECUTABLE}" --version
        OUTPUT_VARIABLE ALTIRO_SHADERC_INFO COMMAND_ERROR_IS_FATAL ANY)
    if(NOT ALTIRO_SHADERC_INFO MATCHES "shaderc 2023\\.8([- \n]|$)")
        message(FATAL_ERROR "The GPU experiment pins shaderc 2023.8; set ALTIRO_GLSLC to that glslc executable")
    endif()
    # Android's SDK Ninja is not necessarily on PATH. Propagate it explicitly
    # into the separate host shader-generator build, without the NDK compilers.
    find_program(ALTIRO_HOST_CC NAMES cc gcc clang cl NO_CMAKE_FIND_ROOT_PATH REQUIRED)
    find_program(ALTIRO_HOST_CXX NAMES c++ g++ clang++ cl NO_CMAKE_FIND_ROOT_PATH REQUIRED)
    set(ALTIRO_SHADER_TOOLCHAIN "${CMAKE_CURRENT_BINARY_DIR}/altiro-shader-toolchain.cmake")
    file(WRITE "${ALTIRO_SHADER_TOOLCHAIN}"
        "set(CMAKE_SYSTEM_NAME \"${CMAKE_HOST_SYSTEM_NAME}\")\n"
        "set(CMAKE_C_COMPILER \"${ALTIRO_HOST_CC}\")\n"
        "set(CMAKE_CXX_COMPILER \"${ALTIRO_HOST_CXX}\")\n"
        "set(CMAKE_MAKE_PROGRAM \"${CMAKE_MAKE_PROGRAM}\" CACHE FILEPATH \"\" FORCE)\n")
    set(GGML_VULKAN_SHADERS_GEN_TOOLCHAIN "${ALTIRO_SHADER_TOOLCHAIN}" CACHE FILEPATH "" FORCE)
endif()
FetchContent_Declare(whisper
    URL https://codeload.github.com/ggml-org/whisper.cpp/tar.gz/927cfce34f31707e17f2bff35c349632fb9e2c3a
    URL_HASH SHA256=41b664fee09e79176ac277b5237debec34f8d74af3c7d71f333f1ec67989ecde
    DOWNLOAD_EXTRACT_TIMESTAMP TRUE
    SOURCE_SUBDIR altiro-no-auto-subdir)
FetchContent_MakeAvailable(whisper)
# The upstream scheduler permits CPU fallback when a requested GPU fails to
# initialize. For this experiment fail explicitly instead of reporting CPU as GPU.
find_package(Python3 COMPONENTS Interpreter REQUIRED)
set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS "${CMAKE_CURRENT_SOURCE_DIR}/../../../../scripts/patch-whisper-gpu.py")
execute_process(COMMAND ${Python3_EXECUTABLE}
    "${CMAKE_CURRENT_SOURCE_DIR}/../../../../scripts/patch-whisper-gpu.py"
    "${whisper_SOURCE_DIR}/src/whisper.cpp"
    "${whisper_SOURCE_DIR}/ggml/src/ggml-vulkan/CMakeLists.txt"
    "${whisper_SOURCE_DIR}/ggml/src/ggml-vulkan/ggml-vulkan.cpp"
    COMMAND_ERROR_IS_FATAL ANY)
add_subdirectory(${whisper_SOURCE_DIR} ${whisper_BINARY_DIR})
add_library(altiro-whisper SHARED whisper-jni.cpp)
target_link_libraries(altiro-whisper PRIVATE whisper)
if(ALTIRO_VULKAN)
    target_include_directories(ggml-vulkan PRIVATE "${vulkan_hpp_SOURCE_DIR}" "${vulkan_headers_SOURCE_DIR}/include")
    target_link_libraries(ggml-vulkan PRIVATE SPIRV-Headers::SPIRV-Headers)
    if(ANDROID AND CMAKE_BUILD_TYPE STREQUAL "Release")
        # Vulkan-Hpp creates enormous DWARF graphs. Keep Release optimization,
        # but omit dispatcher debug metadata that the installed APK strips.
        set_source_files_properties("${whisper_SOURCE_DIR}/ggml/src/ggml-vulkan/ggml-vulkan.cpp"
            TARGET_DIRECTORY ggml-vulkan PROPERTIES COMPILE_OPTIONS "-g0")
    endif()
    target_include_directories(altiro-whisper PRIVATE "${vulkan_headers_SOURCE_DIR}/include")
    target_link_libraries(altiro-whisper PRIVATE Vulkan::Vulkan)
    target_compile_definitions(altiro-whisper PRIVATE ALTIRO_VULKAN=1)
endif()
if(ANDROID)
    target_link_options(altiro-whisper PRIVATE "-Wl,-z,max-page-size=16384" "-Wl,-z,common-page-size=16384")
else()
    find_package(JNI REQUIRED)
    target_include_directories(altiro-whisper PRIVATE ${JNI_INCLUDE_DIRS})
endif()
