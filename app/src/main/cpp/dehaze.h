
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

/* Cpp Scope Timer */
#include <chrono>
#include <functional>

struct Timer {
    using clock = std::chrono::steady_clock;
    using milli = std::chrono::milliseconds;
    using handler = std::function<void(milli)>;

private:
    clock::time_point start, end;
    handler handle;

public:
    Timer(const handler& func)
        : start(clock::now()), handle(func) {
    }

    ~Timer() {
        end = clock::now();
        milli duration = std::chrono::duration_cast<milli>(end - start);
        handle(duration);
    }
};

#include <android/log.h> // log_lib

#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define TAG "cpp"

#include <opencv2/core.hpp>

[[nodiscard]] auto dark_channel(const cv::Mat& rgb, i32 size = 15) -> cv::Mat;

[[nodiscard]] auto bright_patch(const cv::Mat& rgb, const cv::Mat& dark) -> cv::Rect;

[[nodiscard]] auto
transmission_estimate(
    const cv::Mat& rgb, const cv::Scalar& airlight, i32 size = 15
) -> cv::Mat;

[[nodiscard]] auto
transmission_refine(const cv::Mat& rgb, const cv::Mat& estimate) -> cv::Mat;

[[nodiscard]] auto recover_image(
    const cv::Mat& rgb, cv::Mat transmissionMap, const cv::Scalar& airlight,
    f32 minMapVal = 0.1f
) -> cv::Mat;


#endif //PROJECT_THEIA_NATIVE_DEHAZE_H
