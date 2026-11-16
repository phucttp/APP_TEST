# Quay Test — app quay video test sản phẩm

Android 10 trở lên. Kotlin + CameraX + ML Kit (đọc chữ offline, không cần mạng).

## Cách dùng
0. **⚙ CÀI ĐẶT** (làm một lần): tạo **danh sách nhân viên** và **danh sách sheet** (mỗi sheet là một tab trong bảng tính, tab chưa có thì script tự tạo), dán link Apps Script.
1. **THÔNG TIN**: chọn **nhân viên**, **sheet** sẽ ghi vào, và **thời hạn bảo hành** (0/3/6/12/18/24/36 tháng). Ngày hết bảo hành = ngày quay + số tháng, được in lên video và ghi vào Sheet.
2. **Kéo tay** khoanh vùng có mã sản phẩm. Khung được giữ lại cho các sản phẩm sau, nếu đồ gá cố định thì chỉ cần kéo một lần.
3. Bấm **● QUAY**. App lấy nét, đọc mã rồi hiện hộp xác nhận: kiểm tra **mã** (sửa tay được, hoặc **ĐỌC LẠI**) và nhập **tên khách hàng** (app gợi ý các khách đã nhập gần đây).
4. Bấm XÁC NHẬN thì app **đếm ngược 3‑2‑1** (có tiếng bíp) rồi tự quay. Trong lúc đếm ngược, bấm **HUỶ** để dừng lại.
5. Trong lúc quay có thể **TẠM DỪNG / TIẾP TỤC**, xong bấm **■ DỪNG**.
6. Video lưu vào `Movies/QuayTest`, tên file `MÃ_yyyyMMdd_HHmmss.mp4`. Góc video có in mã, NV, KH và ngày giờ.
7. Mỗi video ghi thêm một dòng vào Google Sheet (nếu đã nhập link). Mất mạng thì app giữ lại và gửi bù sau.
8. **VIDEO ĐÃ QUAY**: 50 video gần nhất. Chạm để xem, giữ để xoá.
9. **NHẬT KÝ**: kéo dữ liệu từ Google Sheet về xem, tìm theo mã, khách hoặc nhân viên. Dòng chưa gửi được có nhãn vàng **CHƯA GỬI**. Chạm vào một dòng để mở video đó nếu nó còn trong máy.

Vạch màu dưới thanh trên cùng: xanh dương = chờ, đỏ = đang quay, vàng = đang tạm dừng.

## Nối Google Sheet
1. Mở Google Sheet (link mặc định đã điền sẵn trong **⚙ CÀI ĐẶT → MỞ SHEET**), vào **Tiện ích mở rộng → Apps Script**.
2. Xoá code mẫu, dán nội dung file [google-sheet/Code.gs](google-sheet/Code.gs), lưu lại.
3. **Triển khai → Tùy chọn triển khai mới → Loại: Ứng dụng web**
   - Thực thi dưới dạng: **Tôi**
   - Người có quyền truy cập: **Bất kỳ ai**
4. Bấm Triển khai, cấp quyền, rồi copy **URL ứng dụng web** (dạng `https://script.google.com/macros/s/.../exec`).
5. Trong app: **⚙ CÀI ĐẶT → Link Apps Script**, dán URL vào, bấm **THỬ KẾT NỐI** (phải ra ✔) rồi bấm LƯU.

Sheet `Log` tự tạo với các cột: Mã SP, Ngày, Giờ bắt đầu, Nhân viên, Khách hàng, Tên file, Thời lượng, Thiết bị, Ghi lúc, ID.

> Sửa Code.gs xong phải **Triển khai → Quản lý → Chỉnh sửa → Phiên bản mới**, nếu không thì URL vẫn chạy code cũ.

## Build
```
gradlew assembleDebug
```
APK nằm ở `app/build/outputs/apk/debug/app-debug.apk`.
