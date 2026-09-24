#!/usr/bin/env python3
"""Simula um Mavic Pro voando em círculo e envia a telemetria por UDP (mesmo formato do app).

Serve para testar o receptor e o dashboard sem o drone:
    python tools/simulate.py [--host 127.0.0.1] [--port 14550] [--lat -23.5505] [--lon -46.6333]
"""
import argparse
import json
import math
import socket
import time


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=14550)
    ap.add_argument("--lat", type=float, default=-23.5505, help="latitude do ponto de decolagem")
    ap.add_argument("--lon", type=float, default=-46.6333, help="longitude do ponto de decolagem")
    ap.add_argument("--radius", type=float, default=120.0, help="raio do círculo (m)")
    ap.add_argument("--speed", type=float, default=8.0, help="velocidade horizontal (m/s)")
    ap.add_argument("--hz", type=float, default=10.0)
    args = ap.parse_args()

    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    m_per_deg_lat = 111_320.0
    m_per_deg_lon = 111_320.0 * math.cos(math.radians(args.lat))
    omega = args.speed / args.radius  # rad/s
    start = time.time()
    seq = 0
    next_t = time.time()
    print(f"Simulando voo -> {args.host}:{args.port} ({args.hz:g} Hz). Ctrl+C para sair.")
    try:
        while True:
            t = time.time() - start
            a = omega * t
            # círculo centrado a `radius` metros ao norte da decolagem
            north = args.radius * (1 - math.cos(a))
            east = args.radius * math.sin(a)
            heading = math.degrees(a) % 360           # rumo tangente ao círculo
            yaw = heading - 360 if heading > 180 else heading
            alt = 40 + 10 * math.sin(t / 8)
            vz = 10 / 8 * math.cos(t / 8)
            bat = max(5, 100 - t * 0.05)
            volt = 11400 + (bat - 50) * 8
            msg = {
                "ts": int(time.time() * 1000), "seq": seq,
                "lat": args.lat + north / m_per_deg_lat,
                "lon": args.lon + east / m_per_deg_lon,
                "alt": alt,
                "vx": args.speed * math.cos(math.radians(heading)),
                "vy": args.speed * math.sin(math.radians(heading)),
                "vz": -vz,
                "speed_h": args.speed, "speed_3d": math.hypot(args.speed, vz),
                "pitch": -8 + 2 * math.sin(t), "roll": 12 * math.sin(t / 3), "yaw": yaw,
                "head_dir": int(yaw), "compass_heading": heading, "compass_error": False,
                "sats": 14 + int(2 * math.sin(t / 5)), "gps_level": "LEVEL_5",
                "flight_mode": "P_GPS", "is_flying": True, "motors_on": True,
                "flight_time_s": int(t), "flight_count": 42,
                "ultrasonic_m": 0.0, "ultrasonic_used": False, "vision_pos_used": False,
                "takeoff_alt": 0.0,
                "home_set": True, "home_lat": args.lat, "home_lon": args.lon,
                "wind_warning": "LEVEL_0", "going_home": False, "gohome_state": "NONE",
                "gohome_height": 60, "imu_preheating": False,
                "bat_low_warn": bat < 30, "bat_serious_warn": bat < 15,
                "max_height_reached": False, "max_radius_reached": False,
                "remaining_flight_s": int(bat * 14), "time_to_home_s": int(math.hypot(north, east) / 10) + 5,
                "bat_needed_home_pct": 12, "max_radius_home_m": 2500,
                "bat_pct": int(bat), "bat_v_mv": int(volt), "bat_a_ma": -int(9000 + 800 * math.sin(t)),
                "bat_temp_c": 31 + int(t / 60),
                "bat_remaining_mah": int(3830 * bat / 100), "bat_full_mah": 3830, "bat_design_mah": 3830,
                "bat_discharges": 87, "bat_life_pct": 96,
                "cell1_mv": int(volt / 3) + 8, "cell2_mv": int(volt / 3) - 6, "cell3_mv": int(volt / 3) - 2,
                "cell_delta_mv": 14,
                "obs_nose": 12.5 + 8 * math.sin(t / 6), "obs_tail": None, "obs_right": 20.0, "obs_left": 3.4,
                "uplink_pct": 80 + int(15 * math.sin(t / 4)), "downlink_pct": 70 + int(20 * math.sin(t / 7)),
                "rc_lx": int(300 * math.sin(t / 5)), "rc_ly": int(200 * math.cos(t / 5)),
                "rc_rx": int(500 * math.sin(t / 2)), "rc_ry": int(400 * math.cos(t / 3)),
                "rc_left_dial": 0, "rc_flight_switch": "POSITION_ONE",
                "rc_gohome_btn": False, "rc_pause_btn": False,
                "gimbal_pitch": -30 + 5 * math.sin(t / 4), "gimbal_roll": 0.0, "gimbal_yaw": 0.0,
            }
            sock.sendto((json.dumps(msg) + "\n").encode(), (args.host, args.port))
            seq += 1
            next_t += 1 / args.hz          # agenda fixa: sem deriva do sleep no Windows
            time.sleep(max(0.0, next_t - time.time()))
    except KeyboardInterrupt:
        print("\nEncerrado.")


if __name__ == "__main__":
    main()
