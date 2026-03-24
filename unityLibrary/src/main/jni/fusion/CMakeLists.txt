project(fusion)

set(CMAKE_CXX_STANDARD 11)
set(CMAKE_CXX_STANDARD_REQUIRED True)
set(LIB_FOLDER ${CMAKE_SOURCE_DIR}/../jniLibs/${CMAKE_ANDROID_ARCH_ABI})

find_package(JNI REQUIRED)

include_directories(${JNI_INCLUDE_DIRS})

set(SOURCE_FILES src/fusion.cpp)

add_library(fusion SHARED
        src/fusion.cpp
)

find_package(JNI REQUIRED)

target_include_directories(fusion PUBLIC include)

target_link_libraries(
        fusion
        PUBLIC
        main
        android
        log
        ${LIB_FOLDER}/libdobby.so
        ${LIB_FOLDER}/libcoreclr.so
)