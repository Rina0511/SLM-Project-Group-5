cmake_minimum_required(VERSION 3.22.1)

project("androidSLM")



# Build native-lib.cpp into libnative-lib.so
add_library(
        native-lib
        SHARED
        native-lib.cpp
)

# Tell CMake where prebuilt .so files are
add_library(ggml-base SHARED IMPORTED)
add_library(ggml-cpu  SHARED IMPORTED)
add_library(ggml       SHARED IMPORTED)
add_library(llama      SHARED IMPORTED)

set_target_properties(ggml-base PROPERTIES
        IMPORTED_LOCATION
        ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libggml-base.so)

set_target_properties(ggml-cpu PROPERTIES
        IMPORTED_LOCATION
        ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libggml-cpu.so)

set_target_properties(ggml PROPERTIES
        IMPORTED_LOCATION
        ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libggml.so)

set_target_properties(llama PROPERTIES
        IMPORTED_LOCATION
        ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libllama.so)

# Link everything together
target_link_libraries(
        native-lib
        ggml-base
        ggml-cpu
        ggml
        llama
        log
)