project(fusion)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED True)
set(LIB_FOLDER ${CMAKE_SOURCE_DIR}/../jniLibs/${CMAKE_ANDROID_ARCH_ABI})

find_package(JNI REQUIRED)
find_package(xdl REQUIRED CONFIG)

include_directories(${JNI_INCLUDE_DIRS})

set(SOURCE_FILES src/fusion.cpp)

add_library(fusion SHARED
        src/fusion.cpp
        src/fusion_config.cpp
        src/dotnet.cpp
        src/exports.cpp
        src/logger.cpp
        src/hooking/allocator.cpp
        src/hooking/il2cpp.cpp
        src/hooking/safehook.cpp
        src/hooking/libunity.cpp
        src/utilities/asm.cpp
        src/utilities/library.cpp
)

find_package(JNI REQUIRED)

target_include_directories(fusion PUBLIC include)

target_link_libraries(
        fusion
        PUBLIC
        xdl::xdl
        main
        android
        log
        ${LIB_FOLDER}/libdobby.so
        ${LIB_FOLDER}/libcoreclr.so
)