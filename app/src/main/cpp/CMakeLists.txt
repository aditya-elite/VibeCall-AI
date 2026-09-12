cmake_minimum_required(VERSION 3.22.1)
project("nnapi_inspector" C CXX)

set(CMAKE_CXX_STANDARD 17)

add_library(
    nnapi_inspector
    SHARED
    nnapi_inspector.cpp
)

find_library(log-lib log)
find_library(android-lib android)

target_link_libraries(
    nnapi_inspector
    ${log-lib}
    ${android-lib}
)
