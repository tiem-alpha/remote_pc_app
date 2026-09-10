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

## Nhập tiếng Việt và giọng nói

- Chọn ô cần nhập trên remote, rồi bấm nút bàn phím để mở **Soạn văn bản**.
- Chạm vào ô đệm để mở bàn phím. Gõ, dán hoặc dùng micro của bàn phím Android; có thể sửa dấu và xuống dòng ngay trong ô đệm.
- Chọn kiểu dán phù hợp với ứng dụng đang nhận nội dung, rồi bấm **Gửi**. App chuyển toàn bộ văn bản qua clipboard Unicode, chờ remote xác nhận, gửi thao tác dán và đóng ô đệm. Không còn bước **Chuyển sang remote** hay nút **Dán** riêng.
- Mặc định **Terminal Linux (Ctrl+Shift+V)**. Các lựa chọn khác là **Windows / ứng dụng thường (Ctrl+V)**, **Shift+Insert** và **macOS (Command+V)**. App nhớ lựa chọn gần nhất; đổi lại khi chuyển sang ứng dụng có phím dán khác.
- Bản nháp được giữ khi đóng ô đệm trong cùng phiên, kể cả khi gửi lỗi. Không tự mở bàn phím khi kết nối hoặc mở ô đệm.
- Không gửi phím Enter bổ sung. Văn bản nhiều dòng đi qua clipboard nguyên khối; việc terminal xử lý hoặc thực thi nội dung dán phụ thuộc terminal và shell.

RDP không cung cấp lệnh Paste chung hay thông tin chắc chắn về ứng dụng đang có focus. Vì vậy không thể bảo đảm một kiểu dán chạy trên mọi hệ thống. Chế độ terminal gọi lệnh dán clipboard tương ứng với menu Paste của terminal có phím Ctrl+Shift+V; không mô phỏng nhấn chuột vào menu. Shift+Insert trên một số ứng dụng X11 lấy PRIMARY selection thay vì clipboard RDP, và Command+V phụ thuộc cách server macOS ánh xạ phím. Remote phải bật clipboard RDP và ứng dụng phải hỗ trợ kiểu dán đã chọn. App không thử liên tiếp nhiều kiểu dán vì có thể gây lặp nội dung hoặc kích hoạt lệnh khác.

Nếu remote từ chối clipboard hoặc không xác nhận trong 10 giây, app giữ ô đệm và báo lỗi, không gửi thao tác dán. Xác nhận clipboard chỉ chứng minh remote đã nhận danh sách định dạng, không xác nhận ứng dụng đã chèn văn bản. Đóng ô đệm hoặc mất kết nối huỷ lượt gửi đang chờ.

Khi clone mới, chạy `git submodule update --init --recursive` để lấy FreeRDP đã ghim.
`freerdp-core.gradle` tích hợp thư viện với Android Gradle Plugin của ứng dụng.
`freerdp-overrides` và `freerdp-native-overrides` chứa phần thay thế Java/JNI,
được ghép với nguồn upstream vào thư mục build; không sửa checkout FreeRDP.
Phần JNI gắn mã lượt gửi với phản hồi clipboard theo thứ tự kênh RDP, và nhận UTF-8 chuẩn để giữ đúng tiếng Việt lẫn ký tự ngoài BMP (ví dụ emoji).

Kiểm tra tự động: `./gradlew :app:assembleDebug :app:testDebugUnitTest`.
Các test luồng gửi kiểm tra chờ đúng xác nhận, không dán khi lỗi/hết hạn/huỷ,
không dán hai lần, giải phóng phím bổ trợ khi lỗi, các kiểu dán và chuẩn hoá tiếng Việt.
Sau khi build, chạy `python3 tests/native/run_clipboard_test.py SERIAL` để kiểm tra
code clipboard C thực tế với peer RDP mô phỏng trên điện thoại arm64 (có ART):
thứ tự xác nhận, clipboard bị thay thế/từ chối/tắt, và chuyển UTF-8 sang UTF-16
với tiếng Việt, emoji, xuống dòng. `./gradlew :app:connectedDebugAndroidTest`
kiểm tra JNI trong APK, nút Gửi và việc giữ bản nháp trên thiết bị.

Kiểm tra trên thiết bị: nhập Telex/VNI có sửa dấu, nhập bằng micro, dán chuỗi
`Tiếng Việt: Trường Nguyễn, Đặng Thị Hồng 😀` và văn bản nhiều dòng vào ô đệm;
chọn đúng kiểu dán và bấm **Gửi**, kiểm tra không mất dấu hoặc lặp chữ trên trình soạn thảo remote và terminal.
Thử đóng/mở ô đệm, mất kết nối lúc đang chờ, clipboard bị tắt, và đảm bảo thao tác chạm remote không tự bật bàn phím.
