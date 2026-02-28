#pragma once

/* ---- Sampling ------------------------------------------------------------ */
#define CSI_SAMPLE_RATE_HZ          100
#define CSI_SAMPLE_INTERVAL_MS      (1000 / CSI_SAMPLE_RATE_HZ)   /* 10 ms  */

/* ---- WiFi ---------------------------------------------------------------- */
#define WIFI_CHANNEL                6
#define WIFI_AP_SSID                "WaveGuard-AP"
#define WIFI_AP_PASSWORD            ""                 /* open AP for probing */
#define WIFI_AP_MAX_CONN            4
#define WIFI_STA_SSID               ""                 /* leave blank – STA used for CSI only */
#define WIFI_STA_PASSWORD           ""

/* ---- CSI ----------------------------------------------------------------- */
#define CSI_SUBCARRIER_NUM          52
/* Each subcarrier stores one float amplitude + one float phase */
#define CSI_AMP_BYTES               (CSI_SUBCARRIER_NUM * sizeof(float))   /* 208 B */
#define CSI_PHASE_BYTES             (CSI_SUBCARRIER_NUM * sizeof(float))   /* 208 B */
/*
 * BLE packet layout (428 bytes total):
 *   [0..3]   rssi      (int32_t)
 *   [4..7]   noise     (int32_t)
 *   [8..9]   channel   (uint16_t)
 *   [10..11] bandwidth (uint16_t)
 *   [12..219] amplitude floats  (52 × 4 B)
 *   [220..427] phase floats     (52 × 4 B)
 */
#define CSI_PACKET_HEADER_BYTES     12
#define CSI_PACKET_TOTAL_BYTES      (CSI_PACKET_HEADER_BYTES + CSI_AMP_BYTES + CSI_PHASE_BYTES)  /* 12 + 208 + 208 = 428 */

#define CSI_RING_BUFFER_SIZE        16   /* number of frames to keep in ring */

/* ---- BLE ----------------------------------------------------------------- */
#define BLE_DEVICE_NAME             "WaveGuard-ESP32"
#define BLE_MTU                     512

/*
 * Service UUID: 12345678-1234-1234-1234-123456789abc
 *
 * Characteristics:
 *   CSI data  (NOTIFY)     : 12345678-1234-1234-1234-123456789001
 *   Config    (READ/WRITE) : 12345678-1234-1234-1234-123456789002
 *   Status    (READ)       : 12345678-1234-1234-1234-123456789003
 *
 * The UUIDs are stored as 128-bit little-endian arrays.
 */
#define BLE_SVC_UUID128  { 0xbc,0x9a,0x78,0x56,0x34,0x12,0x34,0x12,\
                           0x34,0x12,0x34,0x12,0x78,0x56,0x34,0x12 }

#define BLE_CHR_CSI_UUID128  { 0x01,0x90,0x78,0x56,0x34,0x12,0x34,0x12,\
                               0x34,0x12,0x34,0x12,0x78,0x56,0x34,0x12 }

#define BLE_CHR_CFG_UUID128  { 0x02,0x90,0x78,0x56,0x34,0x12,0x34,0x12,\
                               0x34,0x12,0x34,0x12,0x78,0x56,0x34,0x12 }

#define BLE_CHR_STS_UUID128  { 0x03,0x90,0x78,0x56,0x34,0x12,0x34,0x12,\
                               0x34,0x12,0x34,0x12,0x78,0x56,0x34,0x12 }

/* Attribute handle index helpers (used in ble_server.c) */
#define BLE_HANDLE_NUM              8   /* total attribute entries in gatt_db */
