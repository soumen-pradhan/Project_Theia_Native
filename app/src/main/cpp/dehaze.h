
#ifndef PROJECT_THEIA_NATIVE_DEHAZE_H
#define PROJECT_THEIA_NATIVE_DEHAZE_H

/* Fixed Width Types */
#include <cstdint>

using i8 = int8_t;
using u8 = uint8_t;
using i16 = int16_t;
using u16 = uint16_t;
using i32 = int32_t;
using u32 = uint32_t;
using i64 = int64_t;
using u64 = uint64_t;
using f32 = float;
using f64 = double;

#include <opencv2/core.hpp>

#include <android/log.h> // log_lib

#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define TAG "cpp"

cv::Mat
dark_channel(cv::Mat& rgb, i32 size = 15.0);

cv::Mat
sobel(cv::Mat& rgb);


#endif //PROJECT_THEIA_NATIVE_DEHAZE_H
