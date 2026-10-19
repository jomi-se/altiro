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
add_subdirectory(${transcribepsy_SOURCE_DIR} ${transcribepsy_BINARY_DIR})
target_include_directories(qvac-parakeet PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}")
target_include_directories(ggml PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}")
add_library(altiro-transcribepsy SHARED transcribepsy-jni.cpp run-control.cpp kernel-hunt.cpp)
target_include_directories(altiro-transcribepsy PRIVATE "${transcribepsy_SOURCE_DIR}/src")
target_include_directories(altiro-transcribepsy PRIVATE "${transcribepsy_SOURCE_DIR}/ggml/src")
target_link_libraries(altiro-transcribepsy PRIVATE qvac-parakeet)
target_link_options(altiro-transcribepsy PRIVATE "-Wl,--exclude-libs,ALL" "-Wl,-Bsymbolic")
if(NOT ANDROID)
    find_package(JNI REQUIRED)
    target_include_directories(altiro-transcribepsy PRIVATE ${JNI_INCLUDE_DIRS})
endif()
if(ALTIRO_VULKAN)
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
