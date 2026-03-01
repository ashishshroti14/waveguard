#pragma once

#include <stdbool.h>
#include <stdint.h>
#include "esp_err.h"

/**
 * @brief Initialise and start the GATT BLE server.
 *
 * Registers the WaveGuard GATT service with three characteristics:
 *   - CSI data   (NOTIFY)
 *   - Config     (READ / WRITE)
 *   - Status     (READ)
 *
 * Must be called after NVS is initialised and before any notification attempt.
 *
 * @return ESP_OK on success.
 */
esp_err_t ble_server_init(void);

/**
 * @brief Send a CSI data notification to all connected and subscribed clients.
 *
 * @param[in] data  Pointer to the raw packet bytes.
 * @param[in] len   Number of bytes to send (≤ negotiated MTU − 3).
 * @return ESP_OK if at least one notification was sent, or no client is
 *         connected (non-fatal), ESP_FAIL on internal error.
 */
esp_err_t ble_server_send_csi_notification(const uint8_t *data, uint16_t len);

/**
 * @return true if at least one BLE central is currently connected.
 */
bool ble_server_is_connected(void);
