cmake_minimum_required(VERSION 3.22.1)
project(negativosimd)

add_library(negativosimd SHARED native-lib.cpp)

target_compile_features(negativosimd PRIVATE cxx_std_17)
target_compile_options(negativosimd PRIVATE -O3)
