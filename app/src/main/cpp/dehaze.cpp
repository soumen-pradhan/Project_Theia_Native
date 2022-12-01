
#include "dehaze.h"

#include <opencv2/imgproc.hpp>

cv::Mat
dark_channel(cv::Mat& rgb, i32 size) {
    cv::Mat channels[3]; // r, g, b
    cv::split(rgb, channels);

    cv::Mat kernel = cv::getStructuringElement(cv::MORPH_RECT, { size, size });

    cv::Mat dark;
    cv::min(channels[0], channels[1], dark); // dark <- min(r, g)
    cv::min(channels[2], dark, dark); // dark <- min(b, dark)
    cv::erode(dark, dark, kernel);

    return dark;
}

cv::Mat
sobel(cv::Mat& rgb) {
    cv::Mat img;
    cv::GaussianBlur(rgb, img, { 3, 3 }, 0);
    cv::cvtColor(img, img, cv::COLOR_RGB2GRAY);

    cv::Mat grad_x, grad_y, grad;
    cv::Sobel(img, grad_x, CV_16S, 1, 0);
    cv::Sobel(img, grad_y, CV_16S, 0, 1);

    cv::convertScaleAbs(grad_x, grad_x);
    cv::convertScaleAbs(grad_y, grad_y);

    cv::addWeighted(grad_x, 0.5, grad_y, 0.5, 0, grad);

    return grad;
}
