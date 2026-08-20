cmake_minimum_required(VERSION 3.22.1)

project("libmpv")

if (NOT JELLYSCOPE_EXTRACTED_JNI_DIR)
    message(FATAL_ERROR "JELLYSCOPE_EXTRACTED_JNI_DIR is required")
endif()

set(JELLYSCOPE_NATIVE_ABI_DIR
    "${JELLYSCOPE_EXTRACTED_JNI_DIR}/${ANDROID_ABI}")

foreach (required_library IN ITEMS libmpv.so libavcodec.so libc++_shared.so)
    if (NOT EXISTS "${JELLYSCOPE_NATIVE_ABI_DIR}/${required_library}")
        message(FATAL_ERROR
            "Missing extracted ${required_library} for ABI ${ANDROID_ABI}")
    endif()
endforeach()

add_library(
    player
    SHARED
    main.cpp
    render.cpp
    log.cpp
    jni_utils.cpp
    property.cpp
    event.cpp
)

add_library(mpv SHARED IMPORTED)
set_target_properties(
    mpv
    PROPERTIES
    IMPORTED_LOCATION "${JELLYSCOPE_NATIVE_ABI_DIR}/libmpv.so"
)

add_library(avcodec SHARED IMPORTED)
set_target_properties(
    avcodec
    PROPERTIES
    IMPORTED_LOCATION "${JELLYSCOPE_NATIVE_ABI_DIR}/libavcodec.so"
)

# The NDK toolchain is told not to generate its own STL copy. Linking the
# audited AAR copy explicitly keeps one reviewed libc++_shared.so in the APK.
add_library(cxx SHARED IMPORTED)
set_target_properties(
    cxx
    PROPERTIES
    IMPORTED_LOCATION "${JELLYSCOPE_NATIVE_ABI_DIR}/libc++_shared.so"
)

target_include_directories(
    player
    PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}/include"
    # ANDROID_STL=none prevents the toolchain from selecting or packaging a
    # second runtime. The bridge still needs the pinned NDK's libc++ headers
    # while it links the audited extracted libc++_shared.so above.
    "${CMAKE_SYSROOT}/usr/include/c++/v1"
)

target_compile_features(player PRIVATE cxx_std_11)
target_compile_options(
    player
    PRIVATE
    -Werror
)
target_link_libraries(player mpv avcodec cxx log)
