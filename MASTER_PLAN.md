# Lộ trình Dự án Urban Air Mobility (UAM) - 45 Ngày

**Ngày bắt đầu:** 24/02/2026 (Ngày 1)
**Ngày kết thúc dự kiến:** 10/04/2026

## 1. Phân bổ Nguồn lực (Resource Allocation)
Dựa trên yêu cầu Java Ecosystem & DevOps:
*   **Backend Developer (Java):** Chịu trách nhiệm Spatial Engine, Vehicle Mgmt, Pathfinding AI.
*   **DevOps Engineer:** Thiết lập Docker, PostgreSQL/PostGIS, CI/CD.
*   **Frontend Developer:** Mapbox/Leaflet integration, Visualization.
*   **QA/Tester:** Kiểm thử thuật toán va chạm và logic tiêu thụ năng lượng.

## 2. Xác định MVP (Minimum Viable Product)
Mục tiêu MVP trong 45 ngày: **"Một hệ thống Backend có khả năng tính toán lộ trình Drone từ điểm A đến điểm B trong không gian 3D, tránh được các vùng cấm bay (NFZ) và tòa nhà cố định, hiển thị kết quả trên Web."**

*   **Tính năng cốt lõi:**
    *   Định nghĩa tọa độ 3D và vật cản (AABB).
    *   Thuật toán tìm đường A* cơ bản.
    *   API tính toán năng lượng tiêu thụ.
    *   Giao diện bản đồ 2D/3D hiển thị tĩnh lộ trình.

## 3. Lộ trình 45 ngày (4 Sprints)

### Sprint 1: The Core (Ngày 1 - 10) - *Hiện tại*
*   **Mục tiêu:** Xây dựng Spatial Engine và Obstacle System.
*   **Task:** Định nghĩa Class `FlightPoint`, `Building`, `AABBCollision`.

### Sprint 2: Intelligence & Energy (Ngày 11 - 22)
*   **Mục tiêu:** Implement Pathfinding AI và Battery Manager.
*   **Task:** Thuật toán A*, công thức tiêu thụ pin.

### Sprint 3: Infrastructure & Registry (Ngày 23 - 35)
*   **Mục tiêu:** Database & API Security.
*   **Task:** Cài đặt PostGIS, Registry System, Docker hóa ứng dụng.

### Sprint 4: Visualization & Polishing (Ngày 36 - 45)
*   **Mục tiêu:** Frontend và Stress Test.
*   **Task:** Tích hợp Mapbox, kiểm thử hiệu năng 100+ drones.

## 4. Giám sát Tiến độ (Progress Tracking)
*   **Ngày 1:** Khởi tạo dự án, thiết lập môi trường. (Hoàn thành 2%)
*   **Công cụ:** Sử dụng file `PROGRESS.md` để cập nhật trạng thái hàng ngày.
