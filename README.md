# Chạy BIG CITY

Mở PowerShell ở thư mục dự án chứa cả `frontend` và `backend`. Cần Node.js 20+, JDK 21+ và Maven 3.9+.

```powershell
npm.cmd --prefix frontend ci
npm.cmd --prefix frontend run build
mvn -f backend/pom.xml package
java -jar backend/target/bigcity-server-1.0.0.jar
```

Mở `http://127.0.0.1:8080`. Lệnh build xuất trang vào `frontend/dist`. Nếu chỉ host bản tĩnh thì dùng thư mục này, gồm cả `assets` và `data`. Cổng cư dân cần Java phục vụ frontend và API cùng origin.

## Mã nguồn backend

Mở `backend/pom.xml` dưới dạng dự án Maven trong IntelliJ, rồi mở `src/main/java/vn/bigcity`. Có thể chạy lớp `vn.bigcity.BigCityServer` với thư mục làm việc là `backend` hoặc thư mục gốc của dự án.

| Tệp trong `src/main/java/vn/bigcity` | Phụ trách |
| --- | --- |
| `BigCityServer.java` | Khởi động máy chủ và tác vụ đối soát |
| `http/BigCityHttpHandler.java` | Đường dẫn API, cookie, JSON và tệp tĩnh |
| `auth/AccountService.java` | Đăng ký, đăng nhập, phiên và quyền quản trị |
| `resident/ResidentRequests.java` | Hồ sơ nhập vai, hỗ trợ và lưu trạng thái đơn nạp |
| `payment/PayOSPayments.java` | Tạo link, hủy và đối soát với payOS |

`data/` chứa nội dung cấu hình. `runtime/` lưu tài khoản và lịch sử giao dịch. Maven đóng gói ứng dụng vào `target/`.

## Tài khoản quản trị

1. Khi máy chủ còn chạy cục bộ, vào **Cổng cư dân** và đăng ký tài khoản cho người quản trị. Với tài khoản đã có, xác minh chủ sở hữu trước khi cấp quyền.
2. Dừng Java. Đặt biến môi trường `BIGCITY_ADMINS` thành danh sách tên đăng nhập đã tạo, ngăn cách bằng dấu phẩy, rồi chạy lại Java.
3. Đăng nhập lại và chọn **Quản trị** trong cổng cư dân để duyệt hồ sơ hoặc trả lời yêu cầu hỗ trợ.

```powershell
$env:BIGCITY_ADMINS = 'ten_quan_tri'
java -jar backend/target/bigcity-server-1.0.0.jar
```

Tên nằm trong danh sách quản trị được dành riêng, nên phải tạo và xác minh tài khoản trước khi đưa vào danh sách. Trên máy chủ thật, đặt `BIGCITY_ORIGIN` thành origin HTTPS của web, phục vụ qua reverse proxy TLS và đặt `BIGCITY_RUNTIME` trên ổ bền vững cho một tiến trình Java. Sao lưu cả `accounts` và `requests` trong thư mục runtime.

## Vận hành

- **Hồ sơ nhập vai** có trạng thái chờ, đã duyệt hoặc cần gửi lại. Người chơi có thể sửa và gửi lại khi được yêu cầu. Cổng web lưu phiên bản hồ sơ gần nhất của mỗi người chơi.
- **Hỗ trợ** cho phép mỗi tài khoản có một yêu cầu đang chờ. Quản trị gửi phản hồi và đóng yêu cầu. Người chơi xem lại các yêu cầu cũ hoặc mở yêu cầu mới sau khi nhận phản hồi.
- **Luật nhập vai** nằm trong `backend/data/rules.json`. Nội dung hiện tại là mẫu để đội ngũ chỉnh trước khi mở cửa.
- **Launcher, trailer, Discord, TikTok, YouTube, thành viên và trạng thái máy chủ** lấy từ `backend/data/site.json`. Điền URL HTTPS chính thức để bật các nút.
- **Hình nền màn hình truy cập** nằm trong `frontend/public/city-entry.png`. Giao diện và cổng cư dân nằm trong `frontend/src`.
- Hồ sơ được duyệt trên web. Việc cấp quyền vào game cần tích hợp với máy chủ FiveM.

## Nạp tiền tự động qua payOS

Tạo kênh thanh toán trong payOS. Đặt `PAYOS_CLIENT_ID`, `PAYOS_API_KEY` và `PAYOS_CHECKSUM_KEY` trong môi trường của tiến trình Java. Trên máy chủ thật, đặt `BIGCITY_ORIGIN` thành địa chỉ HTTPS của web để người chơi quay lại sau khi thanh toán. Khi cấu hình đủ ba khóa, cổng cư dân sẽ cho phép tạo đơn. [Tài liệu payOS](https://payos.vn/docs/)

Người chơi mở **Nạp tiền & số dư**, nhập 10.000–10.000.000 VNĐ và chờ link payOS xuất hiện. Mỗi tài khoản có tối đa một yêu cầu đang chờ. Người chơi có thể quay lại mở link hoặc yêu cầu hủy khi payOS chưa ghi nhận tiền. Link được tạo với thời hạn 30 phút.

Java tự kiểm tra các đơn đang chờ với API payOS theo chu kỳ. Khi payOS xác nhận đúng mã đơn, link và toàn bộ số tiền, Java ghi nhận giao dịch và tính lại số dư từ lịch sử. Java tiếp tục đối soát khi người chơi đóng trình duyệt hoặc máy chủ khởi động lại. Dữ liệu trên URL quay về chỉ dùng để mở lại màn hình số dư.

Quản trị viên có thể xem trạng thái và mã đơn trong **Quản trị**. Với đơn báo cần đối soát, kiểm tra mã đơn trên payOS và xử lý qua mục **Hỗ trợ**. Các yêu cầu chuyển khoản cũ vẫn có thể được xử lý theo sao kê. Số dư hiện dùng trong cổng web.
