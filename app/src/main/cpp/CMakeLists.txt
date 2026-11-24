cmake_minimum_required(VERSION 3.22.1)
project(emre_llama_vlm LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_C_STANDARD 11)

# Disable desktop/optional targets for fast, lean Android arm64-v8a builds
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON ON CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS ON CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS OFF CACHE BOOL "" FORCE)

# Prefer a local checkout at <repo>/third_party/llama.cpp if available;
# otherwise fetch upstream llama.cpp (with libmtmd) during CMake configure.
set(LOCAL_LLAMA_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/llama.cpp")
if(EXISTS "${LOCAL_LLAMA_DIR}/CMakeLists.txt")
    message(STATUS "Using local llama.cpp at ${LOCAL_LLAMA_DIR}")
    add_subdirectory("${LOCAL_LLAMA_DIR}" "${CMAKE_CURRENT_BINARY_DIR}/llama.cpp")
else()
    include(FetchContent)
    FetchContent_Declare(
        llama_cpp
        GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git
        GIT_TAG dfdfca22bce36f66b4cfa3e6c96f0c4d92dac0fe
        )
    FetchContent_MakeAvailable(llama_cpp)
    set(LOCAL_LLAMA_DIR "${llama_cpp_SOURCE_DIR}")
endif()

add_library(emre_llama_vlm SHARED
    emre_llama_vlm_jni.cpp
)

target_include_directories(emre_llama_vlm PRIVATE
    "${LOCAL_LLAMA_DIR}/include"
    "${LOCAL_LLAMA_DIR}/ggml/include"
    "${LOCAL_LLAMA_DIR}/tools/mtmd"
)

target_link_libraries(emre_llama_vlm PRIVATE
    llama
    mtmd
    android
    log
)
