# Kế hoạch Sprint 1: The Core Foundation
**Thời gian:** Ngày 1 - Ngày 10

## Mục tiêu (Sprint Goals)
Xây dựng nền tảng toán học và cấu trúc dữ liệu cơ bản cho hệ thống tọa độ 3D và kiểm tra va chạm.

## Danh sách công việc (Backlog)
1.  **Task 1.1:** Khởi tạo project Spring Boot (Java 17+). (Ngày 1-2)
2.  **Task 1.2:** Xây dựng Class `FlightPoint` (Lat, Lon, Alt) và các phương thức tính toán Euclidean. (Ngày 3)
3.  **Task 1.3:** Xây dựng Class `Obstacle` và `Building` kế thừa từ `AABB`. (Ngày 4-5)
4.  **Task 1.4:** Viết logic `CollisionDetector` để kiểm tra điểm/khối trong không gian. (Ngày 6-7)
5.  **Task 1.5:** Viết Unit Test cho các phép toán hình học. (Ngày 8-9)
6.  **Task 1.6:** Sprint Review & Demo (Check-point 1). (Ngày 10)

## Định nghĩa Hoàn thành (DoD)
*   Mã nguồn được đẩy lên Git.
*   Unit Test pass 100%.
*   Có API basic trả về kết quả kiểm tra va chạm giữa 1 Drone và 1 Tòa nhà.
