cmake_minimum_required(VERSION 3.22.1)
project("memmon")

add_library(memmon SHARED native-lib.cpp)

find_library(log-lib log)
target_link_libraries(memmon ${log-lib})