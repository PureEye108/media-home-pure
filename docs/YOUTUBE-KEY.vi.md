# Khóa YouTube của riêng bạn (khoảng 5 phút, làm một lần)

[English](YOUTUBE-KEY.md)

Nút **Phát video đầu tiên** và **Tìm & chọn video** của điều khiển qua web dùng tính năng tìm kiếm chính thức của YouTube (YouTube Data API v3).
Tính năng này cần một **khóa API**: một mã miễn phí do Google cấp và thuộc về bạn. Media Home Pure không có sẵn khóa nào, nên mỗi người dùng tự tạo khóa của mình.
Mọi thứ khác (ô YouTube, Mở bằng Brave, Tìm trên YouTube) đều hoạt động mà không cần khóa.

**Chi phí**: miễn phí, không cần thẻ tín dụng. Google cấp cho mỗi khóa 10.000 đơn vị mỗi ngày; mỗi lượt tìm kiếm dùng 100 đơn vị, nên bạn có khoảng **100 lượt tìm kiếm mỗi ngày**.

## 1. Tạo khóa

1. Trên máy tính, mở **<https://console.cloud.google.com/>** và đăng nhập bằng tài khoản Google của bạn. Chấp nhận điều khoản nếu được hỏi.
2. Ở phía trên cùng, mở danh sách dự án › **Dự án mới** (New project). Tên: `Media Home Pure` › **Tạo** (Create). Đảm bảo dự án mới đang được chọn.
3. Menu ☰ › **API và dịch vụ › Thư viện** (APIs & Services › Library). Tìm **YouTube Data API v3**, mở ra, rồi nhấn **Bật** (Enable).
4. Menu ☰ › **API và dịch vụ › Thông tin xác thực** (Credentials) › **+ Tạo thông tin xác thực › Khóa API** (Create credentials › API key). Sao chép khóa: khóa bắt đầu bằng `AIza` và có 39 ký tự.

## 2. Bảo vệ khóa (nên làm)

Để không ai khác dùng được hạn mức của khóa, hãy chỉ cho phép khóa dùng với ứng dụng này và chỉ với YouTube:

1. Trong **Thông tin xác thực**, mở khóa (biểu tượng bút chì).
2. **Hạn chế ứng dụng** (Application restrictions) › **Ứng dụng Android** (Android apps) › **Thêm** (Add):
   - **Tên gói** (Package name): `io.github.pureeye108.mediahomepure`
   - **Vân tay chứng chỉ SHA-1** (SHA-1 certificate fingerprint): `57:37:B4:31:04:38:DB:38:EF:E2:97:A0:3C:33:07:E1:A3:35:F4:89`

   Đây là các giá trị cho tệp APK phát hành chính thức. Ứng dụng hiển thị các giá trị cho bản *của bạn* trong hộp thoại Khóa YouTube
   (nếu bạn tự biên dịch ứng dụng, hãy dùng các giá trị đó).
3. **Hạn chế API** (API restrictions) › **Hạn chế khóa** (Restrict key) › đánh dấu **YouTube Data API v3**.
4. **Lưu** (Save). Thay đổi có thể mất vài phút mới có hiệu lực.

## 3. Đưa khóa vào ứng dụng

Dùng một trong các cách sau:

- Trên máy tính bảng: **Cài đặt › Thiết lập › Tìm kiếm YouTube từ điều khiển qua web**, hoặc **Cài đặt › Điều khiển qua web › Khóa YouTube**. Dán › **Lưu**.
- Trong điều khiển qua web trên điện thoại: nhấn **Phát video đầu tiên**; khi trang hỏi khóa, hãy dán khóa › **Lưu khóa**.

Khóa chỉ được lưu trên máy tính bảng. Để xóa khóa: mở cùng hộp thoại đó › **Xóa**.

## Nếu không hoạt động

| Thông báo | Ý nghĩa |
|---|---|
| YouTube từ chối khóa | Gõ sai, chưa bật API, hoặc các giới hạn không khớp (tên gói, SHA-1). Đợi vài phút sau khi thay đổi. |
| Đã hết lượt tìm kiếm miễn phí hôm nay | Đã dùng hết 10.000 đơn vị; hạn mức được đặt lại lúc nửa đêm theo giờ Thái Bình Dương. |
| Không tìm thấy video nào | Thử từ khóa khác. |

API của YouTube tuân theo [Điều khoản dịch vụ của các Dịch vụ API YouTube](https://developers.google.com/youtube/terms/api-services-terms-of-service).
