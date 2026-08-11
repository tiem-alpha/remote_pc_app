# Remote PC

Android client để quản lý và điều khiển Ubuntu qua RDP trên Tailscale. Ubuntu cần bật **GNOME Remote Desktop (RDP)** và điện thoại cần kết nối cùng tailnet bằng ứng dụng Tailscale.

## Trạng thái hiện tại

Phần Android/Kotlin đã sẵn sàng để sử dụng:

- Danh sách máy, thêm/sửa/xoá và kết nối nhanh.
- Kiểm tra dữ liệu nhập: hostname/IP, cổng RDP, username và password.
- Thông tin máy **bao gồm mật khẩu** được mã hoá AES-GCM; khoá nằm trong Android Keystore và không thể export khỏi thiết bị.
- Tuỳ chọn màu 16/24/32-bit được lưu cùng mỗi máy.
- Màn hình phiên kết nối tách biệt với phần UI để gắn native RDP engine.
- Khai báo quyền `INTERNET`.

Ứng dụng dùng FreeRDP native để mở phiên RDP, render desktop và gửi touch/keyboard. Source engine được pin tại `third_party/freerdp` ở bản 3.28.0. Build Android yêu cầu Android NDK 29.0.13113456 và CMake 4.1.2; Gradle sẽ dùng engine này để tạo ABI `arm64-v8a`. `libc++_shared.so` từ đúng bản NDK được đóng gói cùng APK vì OpenH264 cần C++ runtime này khi chạy.

## Chạy project

1. Mở thư mục này bằng Android Studio.
2. Dùng JDK 17+ và Android SDK API 37 theo cấu hình project.
3. Chạy `./gradlew :app:assembleDebug`, hoặc chọn **Run** trong Android Studio.
4. Cài app lên Android, bật Tailscale, sau đó thêm máy với IP `100.x.x.x` (hoặc MagicDNS) và port `3389`.

APK debug sau khi build nằm ở `app/build/outputs/apk/debug/app-debug.apk`.

## FreeRDP

FreeRDP 3.28.0 được vendored từ upstream và Android session activity của họ được khởi chạy nội bộ. Certificate TLS sẽ được engine yêu cầu xác nhận thay vì được bỏ qua. Tham khảo [FreeRDP compilation guide](https://github.com/FreeRDP/FreeRDP/wiki/Compilation) khi cập nhật engine.

## Lưu ý hạ tầng

Không cần mở port Internet hoặc sửa Ubuntu ngoài GNOME Remote Desktop và Tailscale đã nêu. Trước khi debug app, hãy thử cùng thông tin bằng một RDP client đã có để xác nhận endpoint, credential và tailnet hoạt động.
