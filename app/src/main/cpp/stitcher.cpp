// Places each JPEG on an equirectangular canvas using the azimuth and pitch stored at capture.
#include <android/log.h>
#include <jni.h>

#include <opencv2/imgcodecs.hpp>
#include <opencv2/imgproc.hpp>

#include <algorithm>
#include <cmath>
#include <string>
#include <vector>

namespace {

constexpr const char* kTag = "PanoramaStitch";
constexpr int kMaxEdge = 640;
constexpr int kPanoWidth = 1600;
constexpr int kPanoHeight = 800;
constexpr double kHorizontalFovDeg = 60.0;
constexpr double kPi = 3.14159265358979323846;

std::string ToString(JNIEnv* env, jstring value) {
    if (value == nullptr) {
        return std::string();
    }
    const char* utf = env->GetStringUTFChars(value, nullptr);
    std::string out = utf == nullptr ? std::string() : std::string(utf);
    if (utf != nullptr) {
        env->ReleaseStringUTFChars(value, utf);
    }
    return out;
}

struct FloatArray {
    JNIEnv* env;
    jfloatArray array;
    jfloat* data;

    FloatArray(JNIEnv* env_in, jfloatArray array_in)
            : env(env_in), array(array_in), data(nullptr) {
        if (array != nullptr) {
            data = env->GetFloatArrayElements(array, nullptr);
        }
    }

    ~FloatArray() {
        if (data != nullptr) {
            env->ReleaseFloatArrayElements(array, data, JNI_ABORT);
        }
    }
};

struct Shot {
    cv::Mat image;
    cv::Vec3d forward;
    cv::Vec3d right;
    cv::Vec3d up;
    double azimuth;
    double pitch;
    double tan_half_h;
    double tan_half_v;
    double half_h_deg;
    double half_v_deg;
};

double Wrap360(double degrees) {
    degrees = std::fmod(degrees, 360.0);
    if (degrees < 0.0) {
        degrees += 360.0;
    }
    return degrees;
}

double SignedDelta(double from, double to) {
    return Wrap360(to - from + 180.0) - 180.0;
}

cv::Vec3d Normalize(const cv::Vec3d& value) {
    const double length = cv::norm(value);
    if (length < 1e-8) {
        return cv::Vec3d(0.0, 0.0, 1.0);
    }
    return value * (1.0 / length);
}

cv::Vec3d Direction(double azimuth_deg, double pitch_deg) {
    const double azimuth = azimuth_deg * kPi / 180.0;
    const double pitch = pitch_deg * kPi / 180.0;
    return cv::Vec3d(
            std::sin(azimuth) * std::cos(pitch),
            std::cos(azimuth) * std::cos(pitch),
            std::sin(pitch));
}

cv::Vec3b SampleBilinear(const cv::Mat& image, double x, double y) {
    x = std::max(0.0, std::min(x, static_cast<double>(image.cols - 1)));
    y = std::max(0.0, std::min(y, static_cast<double>(image.rows - 1)));
    const int x0 = static_cast<int>(x);
    const int y0 = static_cast<int>(y);
    const int x1 = std::min(x0 + 1, image.cols - 1);
    const int y1 = std::min(y0 + 1, image.rows - 1);
    const double tx = x - x0;
    const double ty = y - y0;
    const cv::Vec3b p00 = image.at<cv::Vec3b>(y0, x0);
    const cv::Vec3b p10 = image.at<cv::Vec3b>(y0, x1);
    const cv::Vec3b p01 = image.at<cv::Vec3b>(y1, x0);
    const cv::Vec3b p11 = image.at<cv::Vec3b>(y1, x1);
    cv::Vec3b out;
    for (int channel = 0; channel < 3; ++channel) {
        const double top = p00[channel] * (1.0 - tx) + p10[channel] * tx;
        const double bottom = p01[channel] * (1.0 - tx) + p11[channel] * tx;
        out[channel] = static_cast<unsigned char>(top * (1.0 - ty) + bottom * ty + 0.5);
    }
    return out;
}

Shot MakeShot(const cv::Mat& image, double azimuth_deg, double pitch_deg) {
    Shot shot;
    shot.image = image;
    shot.azimuth = azimuth_deg;
    shot.pitch = pitch_deg;
    shot.forward = Direction(azimuth_deg, pitch_deg);
    cv::Vec3d world_up(0.0, 0.0, 1.0);
    cv::Vec3d right = shot.forward.cross(world_up);
    if (cv::norm(right) < 1e-3) {
        right = shot.forward.cross(cv::Vec3d(0.0, 1.0, 0.0));
    }
    shot.right = Normalize(right);
    shot.up = Normalize(shot.right.cross(shot.forward));
    const double aspect = static_cast<double>(image.rows) / static_cast<double>(std::max(1, image.cols));
    shot.half_h_deg = kHorizontalFovDeg * 0.5;
    shot.tan_half_h = std::tan(shot.half_h_deg * kPi / 180.0);
    shot.tan_half_v = shot.tan_half_h * aspect;
    shot.half_v_deg = std::atan(shot.tan_half_v) * 180.0 / kPi;
    return shot;
}

bool Covers(const Shot& shot, double azimuth_deg, double pitch_deg) {
    const double azimuth_limit = std::min(
            170.0,
            shot.half_h_deg / std::max(0.25, std::cos(pitch_deg * kPi / 180.0)) + 8.0);
    return std::abs(SignedDelta(shot.azimuth, azimuth_deg)) <= azimuth_limit
            && std::abs(shot.pitch - pitch_deg) <= shot.half_v_deg + 8.0;
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL
Java_com_panorama_app_stitch_NativeStitcher_stitch(
        JNIEnv* env,
        jclass,
        jobjectArray paths,
        jfloatArray azimuth_degrees,
        jfloatArray pitch_degrees,
        jstring output_path) {
    try {
        const jsize count = env->GetArrayLength(paths);
        if (count < 2 || azimuth_degrees == nullptr || pitch_degrees == nullptr) {
            return 1;
        }
        if (env->GetArrayLength(azimuth_degrees) != count || env->GetArrayLength(pitch_degrees) != count) {
            return 1;
        }

        FloatArray azimuths(env, azimuth_degrees);
        FloatArray pitches(env, pitch_degrees);
        if (azimuths.data == nullptr || pitches.data == nullptr) {
            return -1;
        }

        std::vector<Shot> shots;
        shots.reserve(static_cast<size_t>(count));
        for (jsize i = 0; i < count; ++i) {
            jstring path = static_cast<jstring>(env->GetObjectArrayElement(paths, i));
            const std::string file = ToString(env, path);
            env->DeleteLocalRef(path);
            cv::Mat image = cv::imread(file, cv::IMREAD_COLOR);
            if (image.empty()) {
                __android_log_print(ANDROID_LOG_ERROR, kTag, "Unreadable frame: %s", file.c_str());
                continue;
            }
            const int long_edge = std::max(image.cols, image.rows);
            if (long_edge > kMaxEdge) {
                const double scale = static_cast<double>(kMaxEdge) / static_cast<double>(long_edge);
                cv::resize(image, image, cv::Size(), scale, scale, cv::INTER_AREA);
            }
            shots.push_back(MakeShot(image, azimuths.data[i], pitches.data[i]));
        }
        if (shots.size() < 2) {
            return -1;
        }

        cv::Mat accum(kPanoHeight, kPanoWidth, CV_32FC3, cv::Scalar(0, 0, 0));
        cv::Mat weight(kPanoHeight, kPanoWidth, CV_32FC1, cv::Scalar(0));

        for (int y = 0; y < kPanoHeight; ++y) {
            const double pitch = 90.0 - (static_cast<double>(y) + 0.5) / kPanoHeight * 180.0;
            cv::Vec3f* accum_row = accum.ptr<cv::Vec3f>(y);
            float* weight_row = weight.ptr<float>(y);
            for (int x = 0; x < kPanoWidth; ++x) {
                const double azimuth = (static_cast<double>(x) + 0.5) / kPanoWidth * 360.0;
                const cv::Vec3d direction = Direction(azimuth, pitch);
                cv::Vec3f color(0, 0, 0);
                float sum = 0.f;
                for (const Shot& shot : shots) {
                    if (!Covers(shot, azimuth, pitch)) {
                        continue;
                    }
                    const double cam_x = direction.dot(shot.right);
                    const double cam_y = direction.dot(shot.up);
                    const double cam_z = direction.dot(shot.forward);
                    if (cam_z < 0.2) {
                        continue;
                    }
                    const double nx = (cam_x / cam_z) / shot.tan_half_h;
                    const double ny = -(cam_y / cam_z) / shot.tan_half_v;
                    if (nx < -1.0 || nx > 1.0 || ny < -1.0 || ny > 1.0) {
                        continue;
                    }
                    const float feather = static_cast<float>((1.0 - nx * nx) * (1.0 - ny * ny));
                    const double px = (nx + 1.0) * 0.5 * (shot.image.cols - 1);
                    const double py = (ny + 1.0) * 0.5 * (shot.image.rows - 1);
                    const cv::Vec3b sample = SampleBilinear(shot.image, px, py);
                    color[0] += feather * sample[0];
                    color[1] += feather * sample[1];
                    color[2] += feather * sample[2];
                    sum += feather;
                }
                accum_row[x] = color;
                weight_row[x] = sum;
            }
        }

        cv::Mat pano(kPanoHeight, kPanoWidth, CV_8UC3, cv::Scalar(0, 0, 0));
        for (int y = 0; y < kPanoHeight; ++y) {
            const cv::Vec3f* accum_row = accum.ptr<cv::Vec3f>(y);
            const float* weight_row = weight.ptr<float>(y);
            cv::Vec3b* out_row = pano.ptr<cv::Vec3b>(y);
            for (int x = 0; x < kPanoWidth; ++x) {
                if (weight_row[x] <= 0.001f) {
                    continue;
                }
                const cv::Vec3f color = accum_row[x] * (1.f / weight_row[x]);
                out_row[x] = cv::Vec3b(
                        static_cast<unsigned char>(std::max(0.f, std::min(255.f, color[0]))),
                        static_cast<unsigned char>(std::max(0.f, std::min(255.f, color[1]))),
                        static_cast<unsigned char>(std::max(0.f, std::min(255.f, color[2]))));
            }
        }

        const std::string output = ToString(env, output_path);
        const std::vector<int> params = {cv::IMWRITE_JPEG_QUALITY, 90};
        if (!cv::imwrite(output, pano, params)) {
            __android_log_print(ANDROID_LOG_ERROR, kTag, "Could not write %s", output.c_str());
            return -3;
        }
        __android_log_print(ANDROID_LOG_INFO, kTag, "Wrote %s (%dx%d) from %d frames",
                            output.c_str(), pano.cols, pano.rows, static_cast<int>(shots.size()));
        return 0;
    } catch (const cv::Exception& error) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "OpenCV: %s", error.what());
        return -2;
    } catch (const std::exception& error) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "%s", error.what());
        return -2;
    }
}
