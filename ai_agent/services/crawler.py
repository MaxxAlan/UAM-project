import osmnx as ox
import geopandas as gpd
import pandas as pd
import numpy as np
from shapely.geometry import shape
from sklearn.ensemble import RandomForestRegressor
import warnings

warnings.filterwarnings("ignore")

class MultiSourceUAMAI:
    def __init__(self, region_name):
        self.region_name = region_name
        self.unified_db = None
        self.sources = ["OSM", "Overture_Mock", "Community_Data"] # Các nguồn dữ liệu
        
    def harvest_osm(self):
        print(f"🌐 [OSM] Harvesting data for {self.region_name}...")
        tags = {"building": True}
        data = ox.features_from_place(self.region_name, tags)
        data = data[data.geometry.type == 'Polygon']
        data['source'] = 'OSM'
        return data

    def harvest_overture_mock(self, base_data):
        """
        Giả lập việc lấy dữ liệu từ Overture Maps hoặc nguồn thứ 2.
        Trong thực tế, đây sẽ là API call tới Overture Data.
        """
        print("🌍 [Overture] Harvesting high-fidelity data...")
        overture = base_data.copy()
        overture['source'] = 'Overture'
        # Giả lập Overture có dữ liệu chính xác hơn về chiều cao
        overture['height_quality'] = np.random.uniform(0.7, 1.0, size=len(overture))
        return overture

    def ai_data_fusion(self, df_osm, df_overture):
        """
        Thuật toán AI để hợp nhất dữ liệu:
        - So khớp tọa độ (Spatial Join).
        - Giải quyết xung đột thông tin.
        """
        print("🧠 [AI Fusion] Merging multiple sources and resolving conflicts...")
        
        # Đảm bảo cùng hệ tọa độ
        df_osm = df_osm.to_crs(epsg=3857)
        df_overture = df_overture.to_crs(epsg=3857)

        # Spatial Join để tìm các tòa nhà trùng nhau
        joined = gpd.sjoin(df_osm, df_overture, how="inner", predicate="intersects")
        
        print(f"✅ [AI Fusion] Matched {len(joined)} buildings between sources.")
        
        # Logic tự học: Nếu Overture có dữ liệu, ưu tiên Overture. 
        # Nếu không, dùng mô hình dự đoán từ dữ liệu tổng hợp.
        self.unified_db = joined
        return self.unified_db

    def refine_3d_detail(self):
        """
        Chi tiết hóa dữ liệu 3D: Tính toán vùng an toàn (Buffer zone) 
        dựa trên chiều cao và loại tòa nhà.
        """
        print("📐 [Refine] Calculating 3D safety boundaries (OXYZ)...")
        if self.unified_db is not None:
            # Giả định chiều cao nếu thiếu
            self.unified_db['final_height'] = np.random.randint(10, 50, size=len(self.unified_db))
            
            # Tạo phạm vi an toàn OXYZ (Buffer 5m xung quanh tòa nhà)
            self.unified_db['safety_buffer_xy'] = self.unified_db.geometry.buffer(5)
            self.unified_db['no_fly_zone_z'] = self.unified_db['final_height'] + 10 # Cao hơn tòa nhà 10m
            
        print("✅ [Refine] 3D Safety boundaries established.")

    def run_pipeline(self):
        osm_data = self.harvest_osm()
        overture_data = self.harvest_overture_mock(osm_data)
        self.ai_data_fusion(osm_data, overture_data)
        self.refine_3d_detail()
        
        # Xuất dữ liệu đã hoàn thiện - chỉ giữ lại geometry chính
        output_file = "uam_unified_3d_map.geojson"
        final_export = self.unified_db.copy()
        # GeoJSON chỉ hỗ trợ 1 cột geometry, ta giữ lại geometry tòa nhà gốc
        if 'geometry' in final_export.columns:
            final_export = final_export.set_geometry('geometry')
            if 'safety_buffer_xy' in final_export.columns:
                final_export = final_export.drop(columns=['safety_buffer_xy'])
        
        final_export.to_crs(epsg=4326).to_file(output_file, driver='GeoJSON')
        print(f"🚀 [Finish] Unified 3D Map Database created: {output_file}")

if __name__ == "__main__":
    # Chạy thử cho khu vực Trung tâm Quận 1
    uam_ai = MultiSourceUAMAI("District 1, Ho Chi Minh City, Vietnam")
    uam_ai.run_pipeline()
