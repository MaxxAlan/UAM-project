#!/usr/bin/env python3
"""
UAM Data Builder — Overture Maps Multi-City Pipeline
=====================================================
Downloads building footprint & height data for any supported city/country
from Overture Maps S3 (GeoParquet) via DuckDB.

Usage:
    python scripts/data_builder.py --list                  # show all cities
    python scripts/data_builder.py --city hcm              # build HCM data
    python scripts/data_builder.py --city hanoi            # build Hanoi data
    python scripts/data_builder.py --city singapore        # build Singapore data
    python scripts/data_builder.py                         # default = hcm

Output:
    src/main/resources/static/data/buildings_{city_key}_offline.json
"""

import argparse
import json
import os
import sys
import math
import duckdb
import traceback
from pathlib import Path

# ---------------------------------------------------------------------------
# Cities / Countries Registry
# ---------------------------------------------------------------------------
# bbox format: [min_lon, min_lat, max_lon, max_lat] (WGS84)
# limit: max buildings to fetch (None = no limit, use for small cities)

CITIES = {
    # ── VIỆT NAM ─────────────────────────────────────────────────────────
    "hcm": {
        "name": "TP. Hồ Chí Minh",
        "country": "Việt Nam",
        "bbox": [106.55, 10.65, 106.85, 10.90],
        "limit": 150_000,
    },
    "hanoi": {
        "name": "Hà Nội",
        "country": "Việt Nam",
        "bbox": [105.70, 20.85, 106.00, 21.10],
        "limit": 120_000,
    },
    "danang": {
        "name": "Đà Nẵng",
        "country": "Việt Nam",
        "bbox": [108.05, 15.95, 108.30, 16.15],
        "limit": 60_000,
    },
    "haiphong": {
        "name": "Hải Phòng",
        "country": "Việt Nam",
        "bbox": [106.55, 20.78, 106.80, 20.95],
        "limit": 60_000,
    },
    "cantho": {
        "name": "Cần Thơ",
        "country": "Việt Nam",
        "bbox": [105.70, 10.00, 105.85, 10.12],
        "limit": 40_000,
    },
    "nhatrang": {
        "name": "Nha Trang",
        "country": "Việt Nam",
        "bbox": [109.10, 12.20, 109.25, 12.30],
        "limit": 40_000,
    },
    "vungtau": {
        "name": "Vũng Tàu",
        "country": "Việt Nam",
        "bbox": [107.02, 10.30, 107.15, 10.45],
        "limit": 30_000,
    },
    "hue": {
        "name": "Huế",
        "country": "Việt Nam",
        "bbox": [107.53, 16.41, 107.65, 16.52],
        "limit": 30_000,
    },

    # ── ĐÔNG NAM Á ───────────────────────────────────────────────────────
    "singapore": {
        "name": "Singapore",
        "country": "Singapore",
        "bbox": [103.60, 1.20, 104.05, 1.50],
        "limit": 150_000,
    },
    "bangkok": {
        "name": "Bangkok",
        "country": "Thái Lan",
        "bbox": [100.30, 13.55, 100.90, 14.00],
        "limit": 150_000,
    },
    "kuala_lumpur": {
        "name": "Kuala Lumpur",
        "country": "Malaysia",
        "bbox": [101.58, 3.05, 101.80, 3.25],
        "limit": 100_000,
    },
    "jakarta": {
        "name": "Jakarta",
        "country": "Indonesia",
        "bbox": [106.65, -6.35, 106.95, -6.10],
        "limit": 150_000,
    },
    "manila": {
        "name": "Manila",
        "country": "Philippines",
        "bbox": [120.90, 14.45, 121.10, 14.70],
        "limit": 100_000,
    },

    # ── ĐÔNG BẮC Á ───────────────────────────────────────────────────────
    "tokyo": {
        "name": "Tokyo",
        "country": "Nhật Bản",
        "bbox": [139.55, 35.55, 139.90, 35.80],
        "limit": 200_000,
    },
    "seoul": {
        "name": "Seoul",
        "country": "Hàn Quốc",
        "bbox": [126.80, 37.45, 127.15, 37.70],
        "limit": 150_000,
    },
    "hong_kong": {
        "name": "Hong Kong",
        "country": "Trung Quốc (HK)",
        "bbox": [114.00, 22.20, 114.30, 22.55],
        "limit": 100_000,
    },
    "shanghai": {
        "name": "Thượng Hải",
        "country": "Trung Quốc",
        "bbox": [121.30, 31.05, 121.65, 31.35],
        "limit": 200_000,
    },

    # ── ANH / ÂU ─────────────────────────────────────────────────────────
    "london": {
        "name": "London",
        "country": "Vương quốc Anh",
        "bbox": [-0.30, 51.40, 0.10, 51.60],
        "limit": 200_000,
    },
    "paris": {
        "name": "Paris",
        "country": "Pháp",
        "bbox": [2.20, 48.78, 2.45, 48.93],
        "limit": 150_000,
    },
    "dubai": {
        "name": "Dubai",
        "country": "UAE",
        "bbox": [55.10, 25.05, 55.40, 25.30],
        "limit": 100_000,
    },

    # ── MỸ ───────────────────────────────────────────────────────────────
    "new_york": {
        "name": "New York",
        "country": "Hoa Kỳ",
        "bbox": [-74.05, 40.65, -73.90, 40.80],
        "limit": 200_000,
    },
}

# Latest Overture release path on S3
OVERTURE_RELEASE = "2026-06-17.0"
OVERTURE_S3_BASE = f"s3://overturemaps-us-west-2/release/{OVERTURE_RELEASE}/theme=buildings/type=building/*"

# Base output directory
DATA_DIR = Path(__file__).parent.parent / "src" / "main" / "resources" / "static" / "data"

# Height defaults per building subtype/class (in meters)
HEIGHT_DEFAULTS = {
    "house": 7.0, "detached": 7.0, "terrace": 7.0,
    "semidetached_house": 7.0, "bungalow": 4.0,
    "residential": 10.5, "apartments": 20.0,
    "commercial": 20.0, "retail": 8.0, "supermarket": 10.0,
    "hotel": 30.0, "office": 25.0,
    "industrial": 10.0, "warehouse": 8.0, "factory": 12.0,
    "school": 10.0, "hospital": 20.0, "church": 15.0,
    "temple": 12.0, "mosque": 15.0, "government": 15.0,
    "_default": 12.0,
}


# ---------------------------------------------------------------------------
# Core functions
# ---------------------------------------------------------------------------

def estimate_height(height_val, level_val, subtype: str, class_val: str) -> float:
    if height_val is not None:
        try:
            h = float(str(height_val).replace("m", "").strip())
            if h > 0:
                return round(h, 1)
        except (ValueError, TypeError):
            pass
    if level_val is not None:
        try:
            lvl = float(level_val)
            if lvl > 0:
                return round(lvl * 3.5, 1)
        except (ValueError, TypeError):
            pass
    for key in [subtype, class_val]:
        if key and key.lower() in HEIGHT_DEFAULTS:
            return HEIGHT_DEFAULTS[key.lower()]
    return HEIGHT_DEFAULTS["_default"]


def geo_to_polygon_ring(geometry) -> list:
    """Extract outer ring from WKB/WKT geometry → [{latitude, longitude, altitude}]."""
    if geometry is None:
        return []
    try:
        from shapely import wkb, wkt
        if isinstance(geometry, (bytes, bytearray)):
            geom = wkb.loads(bytes(geometry))
        elif isinstance(geometry, str):
            geom = wkt.loads(geometry)
        else:
            return []
        if geom.is_empty:
            return []
        if geom.geom_type == "Polygon":
            coords = list(geom.exterior.coords)
        elif geom.geom_type == "MultiPolygon":
            largest = max(geom.geoms, key=lambda p: p.area)
            coords = list(largest.exterior.coords)
        else:
            return []
        # Remove duplicate closing point
        if len(coords) > 1 and coords[0] == coords[-1]:
            coords = coords[:-1]
        # shapely stores (lon, lat) → convert to {latitude, longitude}
        return [{"latitude": round(lat, 7), "longitude": round(lon, 7), "altitude": 0.0}
                for lon, lat in coords]
    except Exception:
        return []


def build_from_overture(con: duckdb.DuckDBPyConnection, city_cfg: dict) -> list:
    """Query Overture Maps on S3 for the given city config."""
    print("[Overture Maps] Setting up DuckDB spatial + httpfs extensions...")
    con.execute("INSTALL spatial; LOAD spatial;")
    con.execute("INSTALL httpfs; LOAD httpfs;")
    con.execute("SET s3_region='us-west-2';")

    min_lon, min_lat, max_lon, max_lat = city_cfg["bbox"]
    limit = city_cfg.get("limit")
    limit_clause = f"LIMIT {limit}" if limit else ""

    print(f"[Overture Maps] Querying [{min_lat},{min_lon} → {max_lat},{max_lon}] ...")
    if limit:
        print(f"[Overture Maps] Limit: {limit:,} buildings")
    else:
        print("[Overture Maps] No limit — fetching all buildings in area")

    query = f"""
        SELECT
            id,
            names.primary AS name,
            height,
            level,
            subtype,
            class,
            geometry
        FROM read_parquet('{OVERTURE_S3_BASE}', hive_partitioning=1)
        WHERE bbox.xmin <= {max_lon}
          AND bbox.xmax >= {min_lon}
          AND bbox.ymin <= {max_lat}
          AND bbox.ymax >= {min_lat}
        {limit_clause}
    """

    print("[Overture Maps] Running query (may take 2-5 min on first run)...")
    result = con.execute(query).fetchall()
    cols = ["id", "name", "height", "level", "subtype", "class", "geometry"]
    rows = [dict(zip(cols, row)) for row in result]
    print(f"[Overture Maps] Retrieved {len(rows):,} raw building records.")
    return rows


def convert_to_spring_schema(rows: list) -> list:
    """Convert raw Overture/DuckDB rows to Spring Boot Building JSON schema."""
    buildings = []
    skipped = 0
    for i, row in enumerate(rows):
        try:
            polygon = geo_to_polygon_ring(row.get("geometry"))
            if len(polygon) < 3:
                skipped += 1
                continue
            building_id = str(row.get("id", f"building_{i}"))
            name = row.get("name") or f"Tòa nhà {building_id[-6:]}"
            height = estimate_height(
                row.get("height"), row.get("level"),
                str(row.get("subtype") or ""), str(row.get("class") or ""),
            )
            buildings.append({"id": building_id, "name": name,
                               "height": height, "polygon": polygon})
        except Exception:
            skipped += 1
    print(f"[Converter] Converted {len(buildings):,} buildings ({skipped} skipped).")
    return buildings


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def list_cities():
    print("\n  Supported cities / countries:\n")
    print(f"  {'KEY':<18} {'NAME':<25} {'COUNTRY':<20} {'LIMIT'}")
    print("  " + "-" * 75)
    by_country = {}
    for key, cfg in CITIES.items():
        by_country.setdefault(cfg["country"], []).append((key, cfg))
    for country, cities in sorted(by_country.items()):
        for key, cfg in cities:
            limit = f"{cfg['limit']:,}" if cfg.get("limit") else "no limit"
            print(f"  {key:<18} {cfg['name']:<25} {country:<20} {limit}")
    print()


def main():
    parser = argparse.ArgumentParser(
        description="UAM Data Builder — Overture Maps Multi-City Pipeline")
    parser.add_argument("--city", default="hcm",
                        help="City key (default: hcm). Use --list to see all options.")
    parser.add_argument("--list", action="store_true",
                        help="List all supported cities and exit.")
    parser.add_argument("--output", default=None,
                        help="Custom output file path (optional).")
    args = parser.parse_args()

    print("=" * 60)
    print("  UAM Data Builder — Overture Maps Pipeline")
    print("=" * 60)

    if args.list:
        list_cities()
        sys.exit(0)

    city_key = args.city.lower().strip()
    if city_key not in CITIES:
        print(f"\n[ERROR] Unknown city key: '{city_key}'")
        print("  Run with --list to see all supported cities.")
        sys.exit(1)

    city_cfg = CITIES[city_key]
    print(f"\n  City   : {city_cfg['name']}")
    print(f"  Country: {city_cfg['country']}")
    print(f"  Bbox   : {city_cfg['bbox']}")
    print()

    # Determine output path
    if args.output:
        output_path = Path(args.output)
    else:
        DATA_DIR.mkdir(parents=True, exist_ok=True)
        output_path = DATA_DIR / f"buildings_{city_key}_offline.json"

    con = duckdb.connect()
    try:
        rows = build_from_overture(con, city_cfg)
    except Exception as e:
        print(f"\n[ERROR] Failed to fetch from Overture Maps: {e}")
        traceback.print_exc()
        sys.exit(1)
    finally:
        con.close()

    buildings = convert_to_spring_schema(rows)

    if not buildings:
        print("[ERROR] No buildings extracted. Aborting to preserve existing data.")
        sys.exit(1)

    with open(output_path, "w", encoding="utf-8") as f:
        json.dump(buildings, f, ensure_ascii=False, separators=(",", ":"))

    file_size_mb = output_path.stat().st_size / (1024 * 1024)
    print(f"\n[SUCCESS] Wrote {len(buildings):,} buildings to:")
    print(f"  {output_path}")
    print(f"  File size: {file_size_mb:.2f} MB")
    print("\n[NEXT STEPS]")
    print(f"  1. Call POST http://localhost:8080/api/buildings/reload  (no restart needed!)")
    print(f"  2. Refresh the app, select '{city_cfg['name']}'")
    print(f"  3. Buildings for {city_cfg['name']} will now render!")


if __name__ == "__main__":
    main()
