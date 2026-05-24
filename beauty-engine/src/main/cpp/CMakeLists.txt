cmake_minimum_required(VERSION 3.10.2)
project("picme_native")

set(CMAKE_CXX_STANDARD 14)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ── MNN 配置 ────────────────────────────────────────────
set(MNN_ROOT_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../libs/mnn")

# 尝试从下载的 macOS 包复制头文件
set(MNN_HEADER_SOURCE "/tmp/mnn_macos/mnn_3.5.0_macos_x64_arm82_cpu_opencl_metal/Dynamic/MNN.framework/Versions/A/Headers")
if(EXISTS ${MNN_HEADER_SOURCE}/MNNDefine.h AND NOT EXISTS ${MNN_ROOT_DIR}/include/MNNDefine.h)
    file(COPY ${MNN_HEADER_SOURCE}/ DESTINATION ${MNN_ROOT_DIR}/include)
    message(STATUS "Copied MNN headers from ${MNN_HEADER_SOURCE}")
endif()

if(EXISTS "${MNN_ROOT_DIR}/include/MNNDefine.h")
    include_directories(${MNN_ROOT_DIR}/include)
    message(STATUS "MNN headers found at ${MNN_ROOT_DIR}/include")
else()
    message(FATAL_ERROR "MNN headers not found. Please ensure MNN is set up.")
endif()

# MNN 库路径（从 jniLibs）
set(MNN_LIB_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}")

set(MNN_SO_PATH "${MNN_LIB_DIR}/libMNN.so")
if(EXISTS ${MNN_SO_PATH})
    message(STATUS "Using MNN shared library: ${MNN_SO_PATH}")
    add_library(mnn SHARED IMPORTED)
    set_target_properties(mnn PROPERTIES IMPORTED_LOCATION ${MNN_SO_PATH})
else()
    message(FATAL_ERROR "libMNN.so not found at ${MNN_SO_PATH}")
endif()

set(MNN_VULKAN_SO_PATH "${MNN_LIB_DIR}/libMNN_Vulkan.so")
if(EXISTS ${MNN_VULKAN_SO_PATH})
    message(STATUS "Using MNN Vulkan backend: ${MNN_VULKAN_SO_PATH}")
    add_library(mnn_vulkan SHARED IMPORTED)
    set_target_properties(mnn_vulkan PROPERTIES IMPORTED_LOCATION ${MNN_VULKAN_SO_PATH})
endif()

# MNN-LLM 库
set(MNN_LLM_SO_PATH "${MNN_LIB_DIR}/libllm.so")
if(EXISTS ${MNN_LLM_SO_PATH})
    message(STATUS "Using MNN-LLM library: ${MNN_LLM_SO_PATH}")
    add_library(mnn_llm SHARED IMPORTED)
    set_target_properties(mnn_llm PROPERTIES IMPORTED_LOCATION ${MNN_LLM_SO_PATH})
else()
    message(STATUS "libllm.so not found. MNN-LLM support disabled.")
endif()

# MNN Express 库（LLM 依赖）
set(MNN_EXPRESS_SO_PATH "${MNN_LIB_DIR}/libMNN_Express.so")
if(EXISTS ${MNN_EXPRESS_SO_PATH})
    message(STATUS "Using MNN Express library: ${MNN_EXPRESS_SO_PATH}")
    add_library(mnn_express SHARED IMPORTED)
    set_target_properties(mnn_express PROPERTIES IMPORTED_LOCATION ${MNN_EXPRESS_SO_PATH})
endif()

# ── NCNN 配置 ───────────────────────────────────────────
set(NCNN_ROOT_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../libs/ncnn")
set(NCNN_HEADER_DIR "${NCNN_ROOT_DIR}/include")
set(NCNN_LINK_AVAILABLE FALSE)

if(EXISTS "${NCNN_HEADER_DIR}/net.h")
    include_directories(${NCNN_HEADER_DIR})
    message(STATUS "NCNN headers found at ${NCNN_HEADER_DIR}")
    set(NCNN_LINK_AVAILABLE TRUE)
else()
    message(STATUS "NCNN headers not found at ${NCNN_HEADER_DIR}. NCNN GPU inference will be stubbed.")
endif()

set(NCNN_LIB_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}")
set(NCNN_SO_PATH "${NCNN_LIB_DIR}/libncnn.so")

if(NCNN_LINK_AVAILABLE AND EXISTS ${NCNN_SO_PATH})
    message(STATUS "Using NCNN shared library: ${NCNN_SO_PATH}")
    add_library(ncnn SHARED IMPORTED)
    set_target_properties(ncnn PROPERTIES IMPORTED_LOCATION ${NCNN_SO_PATH})
else()
    set(NCNN_LINK_AVAILABLE FALSE)
    message(STATUS "libncnn.so not found at ${NCNN_SO_PATH}. NCNN GPU inference will be stubbed.")
endif()

# ── 源文件 ──────────────────────────────────────────────
# [修改] 始终编译 NCNN 源文件，即使库不存在（提供空实现 stub）
set(NATIVE_SOURCES
    mnn_jni_bridge.cpp
    mnn_face_detector.cpp
    ncnn_jni_bridge.cpp
    ncnn_face_detector.cpp
    llm_jni_bridge.cpp
)

if(NCNN_LINK_AVAILABLE)
    add_definitions(-DNCNN_AVAILABLE=1)
    message(STATUS "NCNN support enabled (GPU inference active)")
else()
    add_definitions(-DNCNN_AVAILABLE=0)
    message(STATUS "NCNN support disabled (GPU inference stubbed)")
endif()

# ── 创建共享库 ──────────────────────────────────────────
add_library(picme_native SHARED ${NATIVE_SOURCES})

# 查找 Android 基础库
find_library(log-lib log)
find_library(android-lib android)
find_library(jnigraphics-lib jnigraphics)

# 编译定义
target_compile_definitions(picme_native PRIVATE
    MNN_USE_THREAD_POOL=1
)

# 链接库
set(LINK_LIBRARIES
    mnn
    mnn_vulkan
    ${log-lib}
    ${android-lib}
    ${jnigraphics-lib}
    vulkan
)

if(TARGET mnn_llm)
    list(APPEND LINK_LIBRARIES mnn_llm)
endif()

if(TARGET mnn_express)
    list(APPEND LINK_LIBRARIES mnn_express)
endif()

if(NCNN_LINK_AVAILABLE)
    list(APPEND LINK_LIBRARIES ncnn)
endif()

target_link_libraries(picme_native ${LINK_LIBRARIES})

# 编译选项
target_compile_options(picme_native PRIVATE
    -O3
    -fexceptions
    -fPIC
    -ffunction-sections
    -fdata-sections
)

set_target_properties(picme_native PROPERTIES
    CXX_VISIBILITY_PRESET hidden
    VISIBILITY_INLINES_HIDDEN ON
)
