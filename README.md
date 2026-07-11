# UAM Drone Path Control & Safety System - Ho Chi Minh City

Hệ thống lập bản đồ không gian 3D, tính toán độ cao an toàn, tránh vật cản (tòa nhà, vùng cấm bay), và tự động thiết lập đường bay tối ưu cho Drone tại khu vực TP.HCM.

---

## 🚀 Các Tính Năng Chính

1. **Bản đồ Tòa nhà & Vùng Cấm Bay (NFZ) tại TP.HCM**:
   - Tự động lấy dữ liệu hình học và độ cao của các tòa nhà thực tế thông qua **OpenStreetMap Overpass API**.
   - Thiết lập các vùng cấm bay (NFZ) cố định xung quanh Sân bay Tân Sơn Nhất và các khu vực quân sự nhạy cảm.

2. **Thuật toán Tìm Đường 3D Tối Ưu**:
   - Sử dụng **3D A* Pathfinding** để tính toán tuyến đường ngắn nhất.
   - **Tối ưu hóa độ cao lý tưởng**: Tự động chuyển đổi giữa bay vượt qua nóc (Flyover) đối với tòa nhà thấp (<70m) và bay vòng qua (Detour) đối với chướng ngại vật cao tầng để tối ưu hóa pin và thời gian bay.
   - **Smooth Transitions**: Tự động vuốt dốc độ cao lên/xuống mượt mà trước và sau khi vượt chướng ngại vật.

3. **Tính toán ETA & Ảnh Hưởng Của Gió**:
   - Áp dụng vector gió thực tế (tốc độ, hướng gió) để tính toán **Ground Speed** (tốc độ thực tế) của drone.
   - Đo đạc chính xác quãng đường bay 3D và ước tính thời gian bay (ETA).

4. **Giao diện HUD Dark Mode Chuyên Nghiệp**:
   - Bản đồ tương tác trực quan với theme tối.
   - Trình giả lập bay (Flight Simulator) cập nhật Telemetry thời gian thực: Tọa độ, Độ cao, Hướng bay (Heading), Tốc độ gió, Tốc độ trung bình, Sai số GPS (DOP).

5. **Tương Thích Thiết Bị**:
   - Xuất nhiệm vụ bay trực tiếp ra file định dạng **QGroundControl (.plan)** (PX4/ArduPilot) và **Google Earth (.kml)**.

---

## 🛠️ Cấu Trúc Mã Nguồn

```text
src/
├── main/
│   ├── java/com/uam/uam_core/
│   │   ├── controller/MapController.java       # Các REST APIs cung cấp cho Frontend
│   │   ├── model/                              # Khai báo cấu trúc dữ liệu
│   │   │   ├── FlightPoint.java                # Điểm bay 3D (Lat, Lon, Alt, Heading)
│   │   │   ├── Building.java                   # Tòa nhà và kiểm tra va chạm Ray-casting
│   │   │   └── NoFlyZone.java                  # Vùng cấm bay hình trụ
│   │   ├── service/                            # Nghiệp vụ xử lý chính
│   │   │   ├── OverpassService.java            # Tải dữ liệu tòa nhà từ OpenStreetMap
│   │   │   └── PathFindingService.java         # Thuật toán tìm đường 3D A*, ETA và tối ưu độ cao
│   │   └── util/DronePlanExporter.java         # Xuất định dạng file .plan và .kml
│   └── resources/
│       └── static/                             # Giao diện Frontend Web
│           ├── css/style.css                   # Thiết kế giao diện HUD Cyberpunk
│           ├── js/app.js                       # Logic bản đồ, giả lập và tương tác API
│           └── index.html                      # Giao diện chính của ứng dụng
```

---

## 💻 Hướng Dẫn Chạy Ứng Dụng

Ứng dụng yêu cầu máy tính cài đặt **Java 17** và **Maven**. Bạn có thể dùng lệnh sau để thiết lập môi trường và chạy ứng dụng:

```powershell
# 1. Cấu hình biến môi trường Java 17 và Maven (nếu cài qua scoop)
$env:PATH = "C:\Users\HoangManh\scoop\apps\openjdk17\current\bin;C:\Users\HoangManh\scoop\apps\maven\current\bin;" + $env:PATH
$env:JAVA_HOME = "C:\Users\HoangManh\scoop\apps\openjdk17\current"

# 2. Khởi chạy ứng dụng Spring Boot
mvn spring-boot:run
```

Sau khi chạy xong, hãy truy cập vào địa chỉ: **[http://localhost:8080](http://localhost:8080)** để bắt đầu trải nghiệm
