project(main)

set(CMAKE_CXX_STANDARD 11)
set(CMAKE_CXX_STANDARD_REQUIRED True)

find_package(JNI REQUIRED)

include_directories(${JNI_INCLUDE_DIRS})

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