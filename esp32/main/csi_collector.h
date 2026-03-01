#pragma once

#include <stdbool.h>
#include <stdint.h>
#include "esp_err.h"

/**
 * @brief Packed CSI data packet ready for BLE transmission.
 *
 * Total size: CSI_PACKET_TOTAL_BYTES (428 bytes).
 * Layout is byte-identical to the layout described in config.h so the Android
 * client can decode it without any extra framing.
 */
typedef struct __attribute__((packed)) {
    int32_t  rssi;                  /**< RSSI in dBm                         */
    int32_t  noise_floor;           /**< Noise floor in dBm                  */
    uint16_t channel;               /**< WiFi channel number                 */
    uint16_t bandwidth;             /**< Channel bandwidth in MHz            */
    float    amplitude[52];         /**< Per-subcarrier amplitude            */
    float    phase[52];             /**< Per-subcarrier phase (radians)      */
} csi_packet_t;

/**
 * @brief Initialise the CSI collector subsystem.
 *
 * Must be called after wifi_manager_init().
 *
 * @return ESP_OK on success.
 */
esp_err_t csi_collector_init(void);

/**
 * @brief Start CSI data collection.
 *
 * @return ESP_OK on success.
 */
esp_err_t csi_collector_start(void);

/**
 * @brief Stop CSI data collection.
 *
 * @return ESP_OK on success.
 */
esp_err_t csi_collector_stop(void);

/**
 * @brief Copy the latest complete CSI packet into @p out.
 *
 * Blocks for at most @p timeout_ms milliseconds waiting for fresh data.
 *
 * @param[out] out         Destination packet buffer (caller-allocated).
 * @param[in]  timeout_ms  Maximum wait time in milliseconds.
 * @return ESP_OK if a packet was returned, ESP_ERR_TIMEOUT otherwise.
 */
esp_err_t csi_collector_get_latest_packet(csi_packet_t *out, uint32_t timeout_ms);
