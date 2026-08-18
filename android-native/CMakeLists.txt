cmake_minimum_required(VERSION 3.22.1)

project(keiba_lgbm LANGUAGES C CXX)

add_library(lightgbm SHARED IMPORTED)

set_target_properties(
    lightgbm
    PROPERTIES
    IMPORTED_LOCATION
    "${CMAKE_CURRENT_SOURCE_DIR}/../android-libs/${ANDROID_ABI}/lib_lightgbm.so"
)

add_library(
    keiba_lgbm
    SHARED
    keiba_lgbm_jni.cpp
)

target_include_directories(
    keiba_lgbm
    PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}/include"
)

target_link_libraries(
    keiba_lgbm
    lightgbm
)
