
// #include "dehaze.h"
//
// #include <opencv2/imgproc.hpp>
// #include <opencv2/core.hpp>
//
// cv::Mat
// dark_channel(cv::Mat& rgb, i32 size) {
//     cv::Mat channels[3]; // r, g, b
//     cv::split(rgb, channels);
//
//     cv::Mat kernel = cv::getStructuringElement(cv::MORPH_RECT, { size, size });
//
//     cv::Mat dark;
//     cv::min(channels[0], channels[1], dark); // dark <- min(r, g)
//     cv::min(channels[2], dark, dark); // dark <- min(b, dark)
//     cv::erode(dark, dark, kernel);
//
//     return dark;
// }
//
// cv::Mat
// sobel(cv::Mat& rgb) {
//     cv::Mat img;
//     cv::GaussianBlur(rgb, img, { 3, 3 }, 0);
//     cv::cvtColor(img, img, cv::COLOR_RGB2GRAY);
//
//     cv::Mat grad_x, grad_y, grad;
//     cv::Sobel(img, grad_x, CV_16S, 1, 0);
//     cv::Sobel(img, grad_y, CV_16S, 0, 1);
//
//     cv::convertScaleAbs(grad_x, grad_x);
//     cv::convertScaleAbs(grad_y, grad_y);
//
//     cv::addWeighted(grad_x, 0.5, grad_y, 0.5, 0, grad);
//
//     return grad;
// }

#include "dehaze.h"
#include <opencv2/imgproc.hpp>
#include <utility>

auto quadtree_subdivision(
    const cv::Mat& integral, const cv::Rect& patch, u32 depth
) -> cv::Rect;

auto guided_filter(
    const cv::Mat& guide, const cv::Mat& estimate, i32 radius, f64 epsilon
) -> cv::Mat;

auto dark_channel(const cv::Mat& rgb, i32 size) -> cv::Mat {
    cv::Mat channels[3]; // r, g, b
    cv::split(rgb, channels);

    cv::Mat dark;
    cv::min(channels[2], channels[1], dark); // dark <- min(b, g)
    cv::min(channels[0], dark, dark); // dark <- min(r, dark)

    if (size > 1) {
        cv::Mat kernel = cv::getStructuringElement(cv::MORPH_RECT, { size, size });
        cv::erode(dark, dark, kernel, cv::Point(0, 0));
    }

    return dark;
}

auto bright_patch(const cv::Mat& rgb, const cv::Mat& dark) -> cv::Rect {
    i32 H = rgb.rows, W = rgb.cols;

    cv::Mat darkIntg;
    cv::integral(dark, darkIntg, CV_32F);

    auto patch = quadtree_subdivision(darkIntg, { 0, 0, W, H }, 5);
    return patch;
}

/**
 *   +-------+-------+
 *   |       |       |
 *   |   A   |   B   |
 *   |       |       |
 *   +-------A------A+B
 *   |       |       |
 *   |   D   |   C   |
 *   |       |       |
 *   +------A+D---A+B+C+D
 */
auto quadtree_subdivision(
    const cv::Mat& integral, const cv::Rect& patch, u32 depth
) -> cv::Rect {
    if (depth == 0 || patch.width <= 1 || patch.height <= 1) {
        return patch;
    }

    std::array<std::pair<i32, i32>, 4> idx{{{ 0, 0 }, { 1, 0 }, { 1, 1 }, { 0, 1 }}};

    auto blockSum = [&](i32 x, i32 y, i32 w, i32 h) {
        i32 x1 = x + w, y1 = y + h;
        return integral.at<f32>(y, x)
               - integral.at<f32>(y, x1)
               + integral.at<f32>(y1, x1)
               - integral.at<f32>(y1, x);
    };

    auto[x0, y0, w, h] = patch;
    i32 w2 = (w + 1) / 2, h2 = (h + 1) / 2;

    i32 xs[2] = { x0, x0 + w / 2 };
    i32 ys[2] = { y0, y0 + h / 2 };

    f32 sumMax = 0;
    i32 xMax = x0, yMax = y0;

    for (auto&[a, b]: idx) {
        auto sum = blockSum(xs[a], ys[b], w2, h2);
        if (sum > sumMax) {
            sumMax = sum;
            xMax = xs[a];
            yMax = ys[b];
        }
    }

    return quadtree_subdivision(integral, { xMax, yMax, w2, h2 }, depth - 1);
}

auto transmission_estimate(
    const cv::Mat& rgb, const cv::Scalar& airlight, i32 size
) -> cv::Mat {
    cv::Mat estimate;

    auto[ar, ag, ab, _] = airlight.val;
    cv::Scalar inv_airlight{ 1 / ar, 1 / ag, 1 / ab };
    {
        // Timer timer([](auto d) { LOGI("RGB / A:  %lldms", d.count()); });
        // cv::divide(rgb, airlight, estimate); // 12ms
        cv::multiply(rgb, inv_airlight, estimate); // 8ms
    }

    // 1 - omega * Dark(est)
    f32 omega = 0.95f;

    {
        // Timer timer([](auto d) { LOGI("Dark(Est): %lldms", d.count()); });
        estimate = dark_channel(estimate, size); // 5ms
    }

    cv::multiply(estimate, -1 * cv::Scalar(omega), estimate); // 2ms
    cv::add(estimate, cv::Scalar(1), estimate); // 0ms


    return estimate;
}

auto transmission_refine(const cv::Mat& rgb, const cv::Mat& estimate) -> cv::Mat {
    cv::Mat gray;
    cv::cvtColor(rgb, gray, cv::COLOR_RGB2GRAY);

    return guided_filter(gray, estimate, 60, 1e-4);
}

auto guided_filter(
    const cv::Mat& guide, const cv::Mat& estimate, i32 radius, f64 epsilon
) -> cv::Mat {
    cv::Mat refined;
    cv::Size kSize(radius, radius);

    cv::Mat guideMean, estmMean, guideEstmMean;
    cv::boxFilter(guide, guideMean, CV_32F, kSize);
    //cv::imshow("guideMean", guideMean);
    cv::boxFilter(estimate, estmMean, CV_32F, kSize);
    //cv::imshow("estmMean", estmMean);
    cv::boxFilter(guide.mul(estimate), guideEstmMean, CV_32F, kSize);
    //cv::imshow("guideEstmMean", guideEstmMean);
    cv::Mat guideEstmCovariance = guideEstmMean - guideMean.mul(estmMean);
    //cv::imshow("guideEstmCovariance", guideEstmCovariance);

    cv::Mat guide2Mean;
    cv::boxFilter(guide.mul(guide), guide2Mean, CV_32F, kSize);
    //cv::imshow("guide2Mean", guide2Mean);
    cv::Mat guideVariance = guide2Mean - guideMean.mul(guideMean);
    //cv::imshow("guideVariance", guideVariance);

    cv::Mat a = guideEstmCovariance / (guideVariance + cv::Scalar(epsilon));
    //cv::imshow("a", a);
    cv::Mat b = estmMean - a.mul(guideMean);
    //cv::imshow("b", b);

    cv::boxFilter(a, a, CV_32F, kSize);
    cv::boxFilter(b, b, CV_32F, kSize);

    refined = a.mul(guide) + b;
    //cv::imshow("refined", refined);
    return refined;
}

auto recover_image(
    const cv::Mat& rgb, cv::Mat transmissionMap, const cv::Scalar& airlight, f32 minMapVal
) -> cv::Mat {
    transmissionMap = cv::max(transmissionMap, cv::Scalar::all(minMapVal));

    // (I - A) / t + A

    cv::Mat recover;
    cv::subtract(rgb, airlight, recover);

    //cv::divide(recover, estimate, recover);
    std::array<cv::Mat, 3> channels;
    cv::split(recover, channels);
    for (auto& ch: channels) {
        cv::divide(ch, transmissionMap, ch);
    }
    cv::merge(channels, recover);

    cv::add(recover, airlight, recover);

    return recover;
}
