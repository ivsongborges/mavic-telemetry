#!/usr/bin/env python3
"""Recebe telemetria UDP (JSON por linha) do app Mavic Telemetry.

Uso:
    python receiver.py [--host 0.0.0.0] [--port 14550] [--out <pasta>]
                       [--web-host 127.0.0.1] [--web-port 8080]

Dashboard web: http://localhost:8080 (use --web-port 0 para desativar;
--web-host 0.0.0.0 para abrir em outros dispositivos da rede).

Grava <projeto>/logs/telemetry_<timestamp>.jsonl e .csv e mostra uma linha de status no terminal.
Sem dependências externas (Python 3.8+).
"""
import argparse
import csv
import json
import os
import socket
import time

import webserver

# Pasta padrão: <raiz do projeto>/logs, independente de onde o comando é executado.
DEFAULT_OUT = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "logs")

FIELDS = [
    "ts", "seq",
    # posição / GPS
    "lat", "lon", "alt", "sats", "gps_level", "home_set", "home_lat", "home_lon", "takeoff_alt",
    # velocidade e atitude
    "vx", "vy", "vz", "speed_h", "speed_3d", "pitch", "roll", "yaw", "head_dir",
    "compass_heading", "compass_error",
    # estado de voo
    "flight_mode", "is_flying", "motors_on", "flight_time_s", "flight_count",
    "ultrasonic_m", "ultrasonic_used", "vision_pos_used", "imu_preheating",
    # bateria
    "bat_pct", "bat_v_mv", "bat_a_ma", "bat_temp_c", "bat_remaining_mah", "bat_full_mah",
    "bat_design_mah", "bat_discharges", "bat_life_pct",
    "cell1_mv", "cell2_mv", "cell3_mv", "cell4_mv", "cell_delta_mv",
    # retorno à base / avisos
    "wind_warning", "going_home", "gohome_state", "gohome_height", "remaining_flight_s",
    "time_to_home_s", "bat_needed_home_pct", "max_radius_home_m",
    "bat_low_warn", "bat_serious_warn", "max_height_reached", "max_radius_reached",
    # obstáculos (distância mínima por sensor, m)
    "obs_nose", "obs_tail", "obs_right", "obs_left",
    # link de rádio
    "uplink_pct", "downlink_pct",
    # controle remoto
    "rc_lx", "rc_ly", "rc_rx", "rc_ry", "rc_left_dial", "rc_flight_switch",
    "rc_gohome_btn", "rc_pause_btn",
    # gimbal
    "gimbal_pitch", "gimbal_roll", "gimbal_yaw",
]


def fmt(v, spec=".2f"):
    return format(v, spec) if isinstance(v, (int, float)) else "--"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default="0.0.0.0")
    ap.add_argument("--port", type=int, default=14550)
    ap.add_argument("--out", default=DEFAULT_OUT,
                    help="pasta dos logs (padrão: <projeto>/logs)")
    ap.add_argument("--web-host", default="127.0.0.1", help="interface do dashboard web")
    ap.add_argument("--web-port", type=int, default=8080, help="porta do dashboard (0 = desativado)")
    args = ap.parse_args()

    os.makedirs(args.out, exist_ok=True)
    jsonl = csv_f = writer = None  # criados no 1º pacote (não deixa arquivos vazios)

    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.bind((args.host, args.port))
    print(f"Escutando UDP em {args.host}:{args.port} — Ctrl+C para sair")
    print(f"Logs em: {os.path.abspath(args.out)}")

    hub = webserver.Hub()
    if args.web_port:
        url = webserver.start(hub, args.web_host, args.web_port, args.out)
        if url:
            print(f"Dashboard: {url}")

    n = 0
    last_seq = None
    lost = 0
    try:
        while True:
            data, addr = sock.recvfrom(65535)
            try:
                msg = json.loads(data.decode("utf-8"))
            except ValueError:
                continue
            n += 1
            seq = msg.get("seq")
            if isinstance(seq, int):
                if last_seq is not None and seq > last_seq + 1:
                    lost += seq - last_seq - 1
                last_seq = seq
            hub.publish(msg, lost)
            if jsonl is None:
                stamp = time.strftime("%Y%m%d_%H%M%S")
                jsonl = open(os.path.join(args.out, f"telemetry_{stamp}.jsonl"), "w", encoding="utf-8")
                csv_f = open(os.path.join(args.out, f"telemetry_{stamp}.csv"), "w", newline="", encoding="utf-8")
                writer = csv.DictWriter(csv_f, fieldnames=FIELDS, extrasaction="ignore")
                writer.writeheader()
            jsonl.write(json.dumps(msg) + "\n")
            writer.writerow(msg)
            if n % 20 == 0:
                jsonl.flush()
                csv_f.flush()
            print(
                f"\r#{n} perdidos={lost} lat={fmt(msg.get('lat'), '.6f')} lon={fmt(msg.get('lon'), '.6f')} "
                f"alt={fmt(msg.get('alt'), '.1f')}m "
                f"v=({fmt(msg.get('vx'), '.1f')},{fmt(msg.get('vy'), '.1f')},{fmt(msg.get('vz'), '.1f')}) "
                f"yaw={fmt(msg.get('yaw'), '.0f')} sats={msg.get('sats', '--')} "
                f"vh={fmt(msg.get('speed_h'), '.1f')}m/s "
                f"bat={msg.get('bat_pct', '--')}% {msg.get('flight_mode', '')}   ",
                end="", flush=True,
            )
    except KeyboardInterrupt:
        print("\nEncerrado.")
    finally:
        if jsonl is not None:
            jsonl.close()
            csv_f.close()
        sock.close()


if __name__ == "__main__":
    main()
