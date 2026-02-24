from services.crawler import MultiSourceUAMAI
import os

if __name__ == "__main__":
    # Bạn có thể thay đổi địa điểm tại đây khi chạy trên Colab
    LOCATION = "District 1, Ho Chi Minh City, Vietnam"
    
    print(f"🚀 Starting UAM AI Agent for: {LOCATION}")
    uam_ai = MultiSourceUAMAI(LOCATION)
    uam_ai.run_pipeline()
    
    if os.path.exists("uam_unified_3d_map.geojson"):
        print("✅ Success! Output file created: uam_unified_3d_map.geojson")
    else:
        print("❌ Failed to create output file.")
