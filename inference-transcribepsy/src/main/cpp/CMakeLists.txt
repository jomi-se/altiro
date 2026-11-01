cmake_minimum_required(VERSION 3.22)
project(altiro_transcribepsy LANGUAGES C CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(CMAKE_C_VISIBILITY_PRESET hidden)
set(CMAKE_CXX_VISIBILITY_PRESET hidden)
set(CMAKE_VISIBILITY_INLINES_HIDDEN ON)
set(CMAKE_POLICY_VERSION_MINIMUM 3.5 CACHE STRING "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(PARAKEET_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_CPU ON CACHE BOOL "" FORCE)
set(GGML_CPU_REPACK ON CACHE BOOL "" FORCE)
set(GGML_CPU_KLEIDIAI OFF CACHE BOOL "" FORCE)
set(GGML_BLAS OFF CACHE BOOL "" FORCE)
set(GGML_ACCELERATE OFF CACHE BOOL "" FORCE)
set(GGML_METAL OFF CACHE BOOL "" FORCE)
set(GGML_CUDA OFF CACHE BOOL "" FORCE)
set(GGML_OPENCL OFF CACHE BOOL "" FORCE)
set(PARAKEET_COREML OFF CACHE BOOL "" FORCE)
set(PARAKEET_BUILD_EXECUTABLES OFF CACHE BOOL "" FORCE)
set(PARAKEET_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(PARAKEET_INSTALL OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN_DISABLE_COOPMAT ON CACHE BOOL "" FORCE)
set(GGML_VULKAN_DISABLE_COOPMAT2 ON CACHE BOOL "" FORCE)
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(GGML_CPU_ARM_ARCH armv8.2-a+dotprod+fp16 CACHE STRING "" FORCE)
endif()
option(ALTIRO_VULKAN "Build the explicit Vulkan experiment" ${ANDROID})
set(GGML_VULKAN ${ALTIRO_VULKAN} CACHE BOOL "" FORCE)
include(FetchContent)
# Header-only Khronos ABI declarations; the probe never links an OpenCL loader.
FetchContent_Declare(opencl_headers
    URL https://codeload.github.com/KhronosGroup/OpenCL-Headers/tar.gz/refs/tags/v2024.10.24
    URL_HASH SHA256=159f2a550592bae49859fee83d372acd152328fdf95c0dcd8b9409f8fad5db93
    SOURCE_SUBDIR altiro-no-auto-subdir
    DOWNLOAD_EXTRACT_TIMESTAMP TRUE)
FetchContent_MakeAvailable(opencl_headers)
# Exact upstream vendor kernels, compiled on-device through ordinary OpenCL access.
FetchContent_Declare(acl_kernels
    URL https://codeload.github.com/ARM-software/ComputeLibrary/tar.gz/80bbedb606746dcc1451cffedf3931d9fec3f53c
    URL_HASH SHA256=1afeccd2c8f757cc26b130360d62b7f0a5da590d732626a94f385c5070b3fb92
    SOURCE_SUBDIR altiro-no-auto-subdir
    DOWNLOAD_EXTRACT_TIMESTAMP TRUE)
FetchContent_MakeAvailable(acl_kernels)
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
    configure_file("${CMAKE_CURRENT_SOURCE_DIR}/shader-toolchain.cmake.in"
        "${ALTIRO_SHADER_TOOLCHAIN}" @ONLY)
    set(GGML_VULKAN_SHADERS_GEN_TOOLCHAIN "${ALTIRO_SHADER_TOOLCHAIN}" CACHE FILEPATH "" FORCE)
endif()
FetchContent_Declare(transcribepsy
    URL https://codeload.github.com/tether-ai-research/qvac-transcribepsy/tar.gz/364ec36130dc87d2cd46e596598268a6e3313751
    URL_HASH SHA256=1d90e51ac738cd08a57625e228be6094a2dfc8b27a1edf431e065031cd71dcdb
    DOWNLOAD_EXTRACT_TIMESTAMP TRUE
    SOURCE_SUBDIR altiro-no-auto-subdir)
FetchContent_MakeAvailable(transcribepsy)
find_package(Python3 COMPONENTS Interpreter REQUIRED)
set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS "${CMAKE_CURRENT_SOURCE_DIR}/../../../../scripts/patch-transcribepsy.py")
execute_process(COMMAND ${Python3_EXECUTABLE}
    "${CMAKE_CURRENT_SOURCE_DIR}/../../../../scripts/patch-transcribepsy.py"
    "${transcribepsy_SOURCE_DIR}" COMMAND_ERROR_IS_FATAL ANY)
set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS "${CMAKE_CURRENT_SOURCE_DIR}/../../../../scripts/prepare-acl-kernels.py")
execute_process(COMMAND ${Python3_EXECUTABLE}
    "${CMAKE_CURRENT_SOURCE_DIR}/../../../../scripts/prepare-acl-kernels.py"
    "${acl_kernels_SOURCE_DIR}" "${CMAKE_CURRENT_BINARY_DIR}/acl-kernels.inc.h"
    COMMAND_ERROR_IS_FATAL ANY)
add_subdirectory(${transcribepsy_SOURCE_DIR} ${transcribepsy_BINARY_DIR})
target_include_directories(qvac-parakeet PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}")
target_include_directories(ggml PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}")
add_library(altiro-transcribepsy SHARED transcribepsy-jni.cpp run-control.cpp kernel-hunt.cpp packed-kernel.cpp gpu-lab-capabilities.cpp acl-kernel.cpp int8-kernel.cpp)
target_include_directories(altiro-transcribepsy PRIVATE "${opencl_headers_SOURCE_DIR}" "${CMAKE_CURRENT_BINARY_DIR}")
target_include_directories(altiro-transcribepsy PRIVATE "${transcribepsy_SOURCE_DIR}/src")
target_include_directories(altiro-transcribepsy PRIVATE "${transcribepsy_SOURCE_DIR}/ggml/src")
target_link_libraries(altiro-transcribepsy PRIVATE qvac-parakeet ${CMAKE_DL_LIBS})
target_link_options(altiro-transcribepsy PRIVATE "-Wl,--exclude-libs,ALL" "-Wl,-Bsymbolic")
if(NOT ANDROID)
    find_package(JNI REQUIRED)
    target_include_directories(altiro-transcribepsy PRIVATE ${JNI_INCLUDE_DIRS})
endif()
if(ALTIRO_VULKAN)
    set(ALTIRO_PACKED_SHADER_DIR "${CMAKE_CURRENT_BINARY_DIR}/packed-shaders")
    file(MAKE_DIRECTORY "${ALTIRO_PACKED_SHADER_DIR}")
    set(ALTIRO_PACKED_SPV)
    foreach(shader pack-weights pack-activations register4)
        set(output "${ALTIRO_PACKED_SHADER_DIR}/${shader}.spv")
        add_custom_command(OUTPUT "${output}"
            COMMAND "${Vulkan_GLSLC_EXECUTABLE}" --target-env=vulkan1.1 -O
                "${CMAKE_CURRENT_SOURCE_DIR}/packed-shaders/${shader}.comp" -o "${output}"
            DEPENDS "${CMAKE_CURRENT_SOURCE_DIR}/packed-shaders/${shader}.comp"
            VERBATIM)
        list(APPEND ALTIRO_PACKED_SPV "${output}")
    endforeach()
    add_custom_command(OUTPUT "${ALTIRO_PACKED_SHADER_DIR}/packed-spirv.h"
        COMMAND "${Python3_EXECUTABLE}" "${CMAKE_CURRENT_SOURCE_DIR}/packed-shaders/embed.py"
            "${ALTIRO_PACKED_SHADER_DIR}/packed-spirv.h" "${ALTIRO_PACKED_SHADER_DIR}"
        DEPENDS ${ALTIRO_PACKED_SPV} "${CMAKE_CURRENT_SOURCE_DIR}/packed-shaders/embed.py"
        VERBATIM)
    target_sources(altiro-transcribepsy PRIVATE "${ALTIRO_PACKED_SHADER_DIR}/packed-spirv.h")
    target_include_directories(altiro-transcribepsy PRIVATE "${ALTIRO_PACKED_SHADER_DIR}")
    set(ALTIRO_INT8_SHADER_DIR "${CMAKE_CURRENT_BINARY_DIR}/int8-shaders")
    file(MAKE_DIRECTORY "${ALTIRO_INT8_SHADER_DIR}")
    set(ALTIRO_INT8_SPV)
    foreach(shader pack-weights quant-activations dot4)
        set(output "${ALTIRO_INT8_SHADER_DIR}/${shader}.spv")
        add_custom_command(OUTPUT "${output}"
            COMMAND "${Vulkan_GLSLC_EXECUTABLE}" --target-env=vulkan1.1 -O
                "${CMAKE_CURRENT_SOURCE_DIR}/int8-shaders/${shader}.comp" -o "${output}"
            DEPENDS "${CMAKE_CURRENT_SOURCE_DIR}/int8-shaders/${shader}.comp"
            VERBATIM)
        list(APPEND ALTIRO_INT8_SPV "${output}")
    endforeach()
    add_custom_command(OUTPUT "${ALTIRO_INT8_SHADER_DIR}/int8-spirv.h"
        COMMAND "${Python3_EXECUTABLE}" "${CMAKE_CURRENT_SOURCE_DIR}/int8-shaders/embed.py"
            "${ALTIRO_INT8_SHADER_DIR}/int8-spirv.h" "${ALTIRO_INT8_SHADER_DIR}"
        DEPENDS ${ALTIRO_INT8_SPV} "${CMAKE_CURRENT_SOURCE_DIR}/int8-shaders/embed.py"
        VERBATIM)
    target_sources(altiro-transcribepsy PRIVATE "${ALTIRO_INT8_SHADER_DIR}/int8-spirv.h")
    target_include_directories(altiro-transcribepsy PRIVATE "${ALTIRO_INT8_SHADER_DIR}")
    target_include_directories(ggml-vulkan PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}" "${vulkan_hpp_SOURCE_DIR}" "${vulkan_headers_SOURCE_DIR}/include")
    target_link_libraries(ggml-vulkan PRIVATE SPIRV-Headers::SPIRV-Headers)
    set_source_files_properties("${transcribepsy_SOURCE_DIR}/ggml/src/ggml-vulkan/ggml-vulkan.cpp"
        TARGET_DIRECTORY ggml-vulkan PROPERTIES COMPILE_OPTIONS "-g0")
    target_include_directories(altiro-transcribepsy PRIVATE "${vulkan_headers_SOURCE_DIR}/include")
    target_link_libraries(altiro-transcribepsy PRIVATE Vulkan::Vulkan)
    target_compile_definitions(altiro-transcribepsy PRIVATE ALTIRO_VULKAN=1)
endif()
if(ANDROID)
    target_link_options(altiro-transcribepsy PRIVATE "-Wl,-z,max-page-size=16384")
    target_link_libraries(altiro-transcribepsy PRIVATE log)
endif()
