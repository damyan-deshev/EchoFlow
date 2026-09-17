cmake_minimum_required(VERSION 3.22)
project(echoflow_supertonic LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(MNN_LIB_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}")

    add_library(MNN SHARED IMPORTED)
    set_target_properties(MNN PROPERTIES
        IMPORTED_LOCATION "${MNN_LIB_DIR}/libMNN.so"
        INTERFACE_INCLUDE_DIRECTORIES "${CMAKE_CURRENT_SOURCE_DIR}/third_party/mnn"
    )

    add_library(MNNExpress SHARED IMPORTED)
    set_target_properties(MNNExpress PROPERTIES
        IMPORTED_LOCATION "${MNN_LIB_DIR}/libMNN_Express.so"
    )

    add_library(echoflow_supertonic SHARED supertonic_jni.cpp)
    target_link_libraries(echoflow_supertonic PRIVATE MNN MNNExpress log dl)
else()
    # EchoFlow's debug build also targets x86_64 emulators. The published MNN
    # mobile GPU runtime is arm64-only, so keep the app buildable there and
    # report the unsupported ABI cleanly at runtime.
    add_library(echoflow_supertonic SHARED supertonic_stub.cpp)
    target_link_libraries(echoflow_supertonic PRIVATE log)
endif()

target_link_options(echoflow_supertonic PRIVATE "-Wl,-z,max-page-size=16384")
