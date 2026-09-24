# Mavic Pro — Telemetria em tempo real (DJI Mobile SDK V4)

```
Mavic Pro ──rádio──> Controle remoto ──USB──> Celular Android (app) ──Wi-Fi/UDP──> PC (receiver.py)
```

O Mavic Pro (original) **só é suportado pelo Mobile SDK V4**; o MSDK V5 não o suporta.
O SDK V4 roda no Android/iOS, então o celular conectado ao controle remoto por USB é a ponte:
o app lê os estados do drone e os envia como JSON via UDP (10 Hz) para o PC.

## Estrutura
- `android/` — app Android (Java, SDK 4.16.4): registra o SDK e transmite telemetria.
- `receiver/receiver.py` — receptor UDP no PC; mostra no terminal, grava `.jsonl` + `.csv` em `logs/` e serve o dashboard.
- `receiver/webserver.py` + `receiver/web/index.html` — servidor HTTP/SSE e a página do dashboard (mapa, instrumentos).
- `tools/simulate.py` — simulador de voo para testar o dashboard sem o drone.

## Requisitos
- Android Studio (Hedgehog ou mais novo) e JDK 11+ (o Android Studio já traz).
- Celular Android 5.0+ (API 21), com cabo USB do controle remoto (Lightning/micro-USB/USB-C, conforme o RC).
- Conta em <https://developer.dji.com> → *Apps* → *Create App*:
  - Tipo: **Android SDK**, package name: `com.dji.mavictelemetry` (ou o que você usar em `applicationId`).
  - Copie o **App Key**.
- Python 3.8+ no PC (sem dependências).

## Passo a passo
1. Crie `android/local.properties` (além do `sdk.dir` que o Android Studio gera):
   ```
   DJI_APP_KEY=seu_app_key_aqui
   ```
2. Abra a pasta `android/` no Android Studio, aguarde o Gradle sync (o wrapper `.jar` é gerado automaticamente).
3. Ligue o celular ao PC com depuração USB e rode o app (▶). O **primeiro registro exige internet** no celular.
4. No PC, descubra o IP (`ipconfig`) e rode, com o PC e o celular na **mesma rede Wi-Fi**:
   ```bash
   python receiver/receiver.py --port 14550
   ```
   Libere a porta UDP 14550 no Firewall do Windows se necessário.
5. Ligue o drone e o controle remoto, e conecte o RC ao celular por USB (o app abre sozinho; aceite a permissão de acessório).
6. No app: informe o IP do PC e a porta, toque em **Iniciar envio**.

> O cabo USB ocupa a porta do celular; use Wi-Fi (ou hotspot do PC) para o envio. Não é necessário o app oficial DJI GO 4 — feche-o para não disputar a conexão.

## Dashboard web e mapa
Ao rodar o `receiver.py`, ele também serve um dashboard em **<http://localhost:8080>** (só biblioteca padrão do Python).
- **Mapa** (OpenStreetMap, com alternância para satélite Esri): posição do drone em tempo real, ícone girando com o rumo,
  trilha do voo, ponto de decolagem (H), "Seguir drone", "Centralizar" e "Limpar trilha".
- **Painéis:** altitude, velocidades, distância até o ponto de decolagem, bateria (células, corrente, potência),
  satélites/GPS, horizonte artificial, bússola, link de rádio, sticks do RC, obstáculos, alertas,
  gráficos dos últimos 5 min e tabela com todos os campos.
- Status "Ao vivo / Sem dados há N s", pacotes recebidos e perdidos.
- O navegador precisa de internet (Leaflet via CDN e tiles do mapa); a telemetria em si é local.

Opções: `--web-port 8080` (`0` desativa), `--web-host 127.0.0.1`. Para abrir em outro dispositivo da rede
(ex.: tablet), use `--web-host 0.0.0.0` e acesse `http://IP-DO-PC:8080` (libere a porta no firewall; não há autenticação).

### Replay de voos salvos
Clique em **Replay** no topo do dashboard para revisar um voo gravado (`.csv` ou `.jsonl`):
- escolha um log da lista (pasta `logs/` do receptor), clique em **Abrir arquivo…** ou **arraste o arquivo** para a página;
- a **barra de progresso** leva a qualquer instante do voo; há também play/pause, ±5 s e velocidade de 0,5× a 30×;
- o mapa mostra o voo inteiro tracejado e a trilha já percorrida; todos os painéis refletem o instante escolhido;
- os gráficos passam a mostrar o **voo completo**; clique neles para saltar para aquele ponto;
- atalhos: `Espaço` reproduz/pausa, `←`/`→` voltam/avançam 5 s (com `Shift`, 30 s).

Enquanto está em Replay a telemetria ao vivo é ignorada (mas continua sendo gravada); volte com **Ao vivo**.
O receptor só cria os arquivos de log quando chega o primeiro pacote, então abrir só para revisar voos não gera arquivos vazios.

**Testar sem o drone:** em outro terminal, `python tools/simulate.py` simula um voo circular e alimenta o dashboard.

## Onde ficam os logs
Por padrão em `<projeto>/logs/` (ex.: `C:\Code\mavic-telemetry\logs\telemetry_AAAAMMDD_HHMMSS.csv` e `.jsonl`),
não importa de onde o comando é executado. Para outra pasta: `--out C:\Dados\voos`.
O `.jsonl` guarda tudo o que chegou; o `.csv` tem as colunas fixas de `FIELDS` em `receiver.py`.

## Campos enviados (JSON, 1 objeto por datagrama, 10 Hz)
| Grupo | Campos |
|---|---|
| Tempo | `ts` (ms epoch) |
| GPS / posição | `lat`, `lon`, `alt` (m, rel. decolagem), `sats`, `gps_level`, `home_set`, `home_lat`, `home_lon`, `takeoff_alt` |
| Velocidade | `vx`, `vy`, `vz` (m/s, NED), `speed_h` (horizontal), `speed_3d` |
| Atitude / bússola | `pitch`, `roll`, `yaw`, `head_dir`, `compass_heading`, `compass_error` |
| Estado de voo | `flight_mode`, `is_flying`, `motors_on`, `flight_time_s`, `flight_count`, `ultrasonic_m`, `ultrasonic_used`, `vision_pos_used`, `imu_preheating` |
| Bateria | `bat_pct`, `bat_v_mv`, `bat_a_ma`, `bat_temp_c`, `bat_remaining_mah`, `bat_full_mah`, `bat_design_mah`, `bat_discharges`, `bat_life_pct`, `cell1_mv`..`cell4_mv`, `cell_delta_mv` |
| Retorno à base / avisos | `wind_warning`, `going_home`, `gohome_state`, `gohome_height`, `remaining_flight_s`, `time_to_home_s`, `bat_needed_home_pct`, `max_radius_home_m`, `bat_low_warn`, `bat_serious_warn`, `max_height_reached`, `max_radius_reached` |
| Obstáculos (m) | `obs_nose`, `obs_tail`, `obs_right`, `obs_left` (distância mínima por sensor de visão) |
| Link de rádio | `uplink_pct`, `downlink_pct` |
| Controle remoto | `rc_lx`, `rc_ly`, `rc_rx`, `rc_ry` (-660..660), `rc_left_dial`, `rc_flight_switch`, `rc_gohome_btn`, `rc_pause_btn` |
| Gimbal | `gimbal_pitch`, `gimbal_roll`, `gimbal_yaw` |

Campos que o drone/RC não reportar ficam vazios no CSV. **Rotação dos motores (RPM) não é exposta pelo SDK V4.**
Para adicionar campos, edite `TelemetryStreamer.java` (callbacks) e `FIELDS` em `receiver.py`.

## Problemas comuns
- **Registro falha**: package name diferente do cadastrado, App Key errado em `local.properties`, ou sem internet.
- **Produto não conecta**: cabo USB só de carga, RC desligado, ou DJI GO 4 aberto.
- **Sem dados no PC**: IP errado, redes diferentes, ou firewall bloqueando UDP.
- Este código não foi compilado/testado com hardware; se o Gradle reclamar de versão do AGP/JDK, ajuste `android/build.gradle`.
