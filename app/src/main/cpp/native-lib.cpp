
#include <jni.h>

#include <opencv2/core.hpp>
#include <opencv2/imgproc.hpp>

#include <android/bitmap.h> // jnigraphics_lib

#include "dehaze.h"

void mat2bitmap(JNIEnv* env, cv::Mat& src, jobject bitmap, bool premultiply_alpha);

void rotate_mat(cv::Mat& mat, u32 rotation);

auto mat_type(i32 type) {
    std::string r;

    u8 depth = type & CV_MAT_DEPTH_MASK;
    u8 chans = 1 + (type >> CV_CN_SHIFT);

    switch (depth) {
        case CV_8U: r = "8U";
            break;
        case CV_8S: r = "8S";
            break;
        case CV_16U: r = "16U";
            break;
        case CV_16S: r = "16S";
            break;
        case CV_32S: r = "32S";
            break;
        case CV_32F: r = "32F";
            break;
        case CV_64F: r = "64F";
            break;
        default: r = "User";
            break;
    }

    r += "C";
    r += (chans + '0');

    return r;
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_projecttheianative_KotlinExtensionsKt_processYuvBuffer(
    JNIEnv* env, jclass clazz,
    jint img_width, jint img_height,
    jobject plane0, jint planeY_row_stride,
    jobject plane1, jint planeU_row_stride,
    jobject plane2, jint planeV_row_stride,
    jboolean interleaved, jobject bitmap
) {
    u8* planeY = static_cast<u8*>(env->GetDirectBufferAddress(plane0));
    u8* planeU = static_cast<u8*>(env->GetDirectBufferAddress(plane1));
    u8* planeV = static_cast<u8*>(env->GetDirectBufferAddress(plane2));

    cv::Mat rgb(img_height + img_height / 2, img_width, CV_8UC1);

    if (interleaved) {  // Chroma channels are interleaved
        // 2 assertions here
        cv::Mat y_mat(
            img_height, img_width, CV_8UC1,
            planeY, planeY_row_stride
        );
        cv::Mat uv1_mat(
            img_height / 2, img_width / 2, CV_8UC2,
            planeU, planeU_row_stride
        );
        cv::Mat uv2_mat(
            img_height / 2, img_width / 2, CV_8UC2,
            planeV, planeV_row_stride
        );

        i32 addr_diff = uv2_mat.data - uv1_mat.data; // Naughty-Naughty

        if (addr_diff > 0) { // YYY...UVUV...
            assert(addr_diff == 1);
            cv::cvtColorTwoPlane(
                y_mat, uv1_mat, rgb, cv::COLOR_YUV2RGB_NV12
            );
        } else { // YYY...VUVU...
            assert(addr_diff == -1);
            cv::cvtColorTwoPlane(
                y_mat, uv2_mat, rgb, cv::COLOR_YUV2RGB_NV21
            );
        }
    } else {  // Chroma channels are not interleaved
        std::vector<u8> yuv_bytes(
            img_width * (img_height + img_height / 2));
        i32 yuv_bytes_offset = 0;

        if (planeY_row_stride == img_width) {
            std::copy_n(planeY, img_width * img_height, yuv_bytes.begin());
            yuv_bytes_offset = img_width * img_height;
        } else {
            u32 padding = planeY_row_stride - img_width;

            for (u32 i = 0, planeY_offset = 0; i < img_height; i++) {
                std::copy_n(
                    planeY + planeY_offset, img_width,
                    yuv_bytes.begin() + yuv_bytes_offset
                );

                yuv_bytes_offset += img_width;
                planeY_offset += (i < img_height - 1) ? padding : 0;
            }

            assert(yuv_bytes_offset == img_width * img_height);
        }

        u32 chroma_row_padding = planeU_row_stride - img_width / 2;
        if (chroma_row_padding == 0) {
            /** When row stride of Chroma Channel == img_width, copy entire channels */
            std::copy_n(
                planeU, img_width * img_height / 4,
                yuv_bytes.begin() + yuv_bytes_offset
            );

            yuv_bytes_offset += img_width * img_height / 4;

            std::copy_n(
                planeV, img_width * img_height / 4,
                yuv_bytes.begin() + yuv_bytes_offset
            );
        } else {
            for (u32 i = 0, planeU_offset = 0; i < img_height / 2; i++) {
                std::copy_n(
                    planeU + planeU_offset, img_width / 2,
                    yuv_bytes.begin() + yuv_bytes_offset
                );
                yuv_bytes_offset += img_width / 2;
                planeU_offset += (i < img_height / 2 - 1)
                                 ? chroma_row_padding
                                 : 0;
            }

            for (u32 i = 0, planeV_offset = 0; i < img_height / 2; i++) {
                std::copy_n(
                    planeV + planeV_offset, img_width / 2,
                    yuv_bytes.begin() + yuv_bytes_offset
                );
                yuv_bytes_offset += img_width / 2;
                planeV_offset += (i < img_height / 2 - 1)
                                 ? chroma_row_padding
                                 : 0;
            }
        }

        rgb.push_back(yuv_bytes);
        cv::cvtColor(rgb, rgb, cv::COLOR_YUV2RGB_I420, 3);
    }

    rgb.convertTo(rgb, CV_32FC3, 1.0 / 255.0);

    {
        cv::Mat dark = dark_channel(rgb, 15); // 3ms

        auto brightPatch = bright_patch(rgb, dark);
        auto airlight = cv::mean(rgb(brightPatch)); // 1ms

        cv::Mat estimate = transmission_estimate(rgb, airlight); // 20ms;
        rgb = recover_image(rgb, estimate, airlight); // 6ms

        //auto refined = transmission_refine(bgr, estimate);
    }

    rgb.convertTo(rgb, CV_8UC1, 255, 0);
    mat2bitmap(env, rgb, bitmap, false);

    // cv::Mat res = sobel(rgb);
}

void mat2bitmap(JNIEnv* env, cv::Mat& src, jobject bitmap, bool premultiply_alpha) {
    AndroidBitmapInfo info;
    void* pixels = nullptr;

    try {
        CV_Assert(AndroidBitmap_getInfo(env, bitmap, &info) >= 0);
        CV_Assert(info.format == ANDROID_BITMAP_FORMAT_RGBA_8888 ||
                  info.format == ANDROID_BITMAP_FORMAT_RGB_565);
        CV_Assert(src.dims == 2 && info.height == src.rows &&
                  info.width == src.cols);
        CV_Assert(src.type() == CV_8UC1 || src.type() == CV_8UC3 ||
                  src.type() == CV_8UC4);

        CV_Assert(AndroidBitmap_lockPixels(env, bitmap, &pixels) >= 0);
        CV_Assert(pixels);

        if (info.format == ANDROID_BITMAP_FORMAT_RGBA_8888) {
            cv::Mat tmp(
                static_cast<i32>(info.height),
                static_cast<i32>(info.width),
                CV_8UC4, pixels
            );

            switch (src.type()) {
                case CV_8UC1:
                    // LOGD("mat2bitmap: CV_8UC1 -> RGBA_8888");
                    cv::cvtColor(src, tmp, cv::COLOR_GRAY2RGBA);
                    break;
                case CV_8UC3:
                    // LOGD("mat2bitmap: CV_8UC3 -> RGBA_8888");
                    cv::cvtColor(src, tmp, cv::COLOR_RGB2RGBA);
                    break;
                case CV_8UC4:
                    // LOGD("mat2bitmap: CV_8UC4 -> RGBA_8888");
                    premultiply_alpha
                    ? cv::cvtColor(src, tmp, cv::COLOR_RGBA2mRGBA)
                    : src.copyTo(tmp);
                    break;
            }
        } else if (info.format == ANDROID_BITMAP_FORMAT_RGB_565) {
            cv::Mat tmp(
                static_cast<i32>(info.height),
                static_cast<i32>(info.width),
                CV_8UC2, pixels
            );

            switch (src.type()) {
                case CV_8UC1:
                    // LOGD("mat2bitmap: CV_8UC1 -> RGBA_565");
                    cv::cvtColor(src, tmp, cv::COLOR_GRAY2BGR565);
                    break;
                case CV_8UC3:
                    // LOGD("mat2bitmap: CV_8UC3 -> RGBA_565");
                    cv::cvtColor(src, tmp, cv::COLOR_RGB2BGR565);
                    break;
                case CV_8UC4:
                    // LOGD("mat2bitmap: CV_8UC4 -> RGBA_565");
                    cv::cvtColor(src, tmp, cv::COLOR_RGBA2BGR565);
                    break;
            }
        }

        AndroidBitmap_unlockPixels(env, bitmap);
        // LOGD("Mat converted to bitmap");
    }
    catch (const cv::Exception& exp) {
        AndroidBitmap_unlockPixels(env, bitmap);
        jclass je = env->FindClass("java/lang/Exception");
        env->ThrowNew(je, exp.what());
    }
    catch (...) {
        AndroidBitmap_unlockPixels(env, bitmap);
        jclass je = env->FindClass("java/lang/Exception");
        env->ThrowNew(je, "Unknown Err in JNI Code 'mat2bitmap'");
    }
}

void rotate_mat(cv::Mat& mat, u32 rotation) {
    switch (rotation) {
        case 90:cv::transpose(mat, mat);
            cv::flip(mat, mat, 1);
            break;
        case 180:cv::flip(mat, mat, -1);
            break;
        case 270:cv::transpose(mat, mat);
            cv::flip(mat, mat, 0);
            break;
        default:break;
    }
}
