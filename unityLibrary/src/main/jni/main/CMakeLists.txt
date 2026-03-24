project(unity_jni_loader)

# Set C++ standard
set(CMAKE_CXX_STANDARD 11)
set(CMAKE_CXX_STANDARD_REQUIRED True)

# Find JNI package
find_package(JNI REQUIRED)

# Add JNI include directory
include_directories(${JNI_INCLUDE_DIRS})

# Add main.cpp as source file
set(SOURCE_FILES main.cpp)

add_library(main SHARED
        main.cpp
)

find_package(JNI REQUIRED)

target_include_directories(main PRIVATE ${JNI_INCLUDE_DIRS})

target_link_libraries(
        main
        PRIVATE
        android
        log
        ${JNI_LIBRARIES}
)