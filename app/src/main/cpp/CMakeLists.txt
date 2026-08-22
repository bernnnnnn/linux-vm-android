cmake_minimum_required(VERSION 3.22.1)
project("bernspty")

add_library(bernspty SHARED pty.c)
find_library(log-lib log)
target_link_libraries(bernspty ${log-lib})
